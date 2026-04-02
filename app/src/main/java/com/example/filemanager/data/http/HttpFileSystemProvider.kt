package com.example.filemanager.data.http

import com.example.filemanager.domain.vfs.ChunkedOpenMode
import com.example.filemanager.domain.vfs.ChunkedRandomAccess
import com.example.filemanager.domain.vfs.FileSystemProvider
import com.example.filemanager.domain.vfs.VirtualFile
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

/**
 * 一个“最小可用”的 HTTP 文件系统 Provider：
 * - 通过 GET/HEAD/PUT/DELETE 映射到 VirtualFile 的读写/删除
 * - 主要用于纯 JVM 单测（配合本地 HTTP Server/MockWebServer）验证 VfsManager 的跨 scheme 拷贝能力
 *
 * 注意：这不是完整的 WebDAV/HTTP FS 实现（无目录/列举/重命名语义）。
 */
class HttpFileSystemProvider(
    override val scheme: String = "http",
) : FileSystemProvider {

    private fun parseHttpLastModifiedToEpochMillis(value: String): Long {
        // HTTP-date: e.g. "Sun, 06 Nov 1994 08:49:37 GMT" (RFC 1123 / RFC 7231 IMF-fixdate)
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
        return runCatching { sdf.parse(value)?.time ?: 0L }.getOrDefault(0L)
    }

    override suspend fun getFile(uri: URI): VirtualFile {
        require(uri.scheme == scheme) { "Unsupported scheme: ${uri.scheme}" }
        return HttpVirtualFile(uri)
    }

    override suspend fun createFile(uri: URI): VirtualFile {
        // HTTP 没有“创建空文件”的统一语义；这里返回一个可写的句柄（openOutputStream 会执行 PUT）。
        return getFile(uri)
    }

    override suspend fun createDirectory(uri: URI): VirtualFile {
        throw UnsupportedOperationException("HTTP provider does not support directories")
    }

    override suspend fun delete(uri: URI): Boolean = withContext(Dispatchers.IO) {
        require(uri.scheme == scheme) { "Unsupported scheme: ${uri.scheme}" }
        val conn = open(uri, "DELETE")
        try {
            conn.connect()
            val code = conn.responseCode
            code in listOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_NO_CONTENT, HttpURLConnection.HTTP_NOT_FOUND)
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun rename(sourceUri: URI, destUri: URI): Boolean {
        throw UnsupportedOperationException("HTTP provider does not support rename")
    }

    override suspend fun copy(
        sourceFile: VirtualFile,
        destFile: VirtualFile,
        progressListener: (bytesCopied: Long) -> Unit,
        cancellationSignal: Job?,
        bufferSize: Int,
    ) {
        // 与本地/SAF Provider 一致：以流桥接的方式实现跨 scheme 复制。
        withContext(Dispatchers.IO) {
            sourceFile.openInputStream().use { input ->
                destFile.openOutputStream(append = false).use { output ->
                    val buffer = ByteArray(bufferSize)
                    var total = 0L
                    while (true) {
                        if (cancellationSignal?.isCancelled == true) {
                            throw java.util.concurrent.CancellationException("copy cancelled")
                        }
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        total += read
                        progressListener(total)
                    }
                    output.flush()
                }
            }
        }
    }

    override suspend fun openChunked(uri: URI, mode: ChunkedOpenMode): ChunkedRandomAccess {
        throw UnsupportedOperationException("HTTP provider does not support chunked access")
    }

    private fun open(uri: URI, method: String): HttpURLConnection {
        val url = URL(uri.toString())
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = true
            connectTimeout = 5_000
            readTimeout = 5_000
        }
    }

    private inner class HttpVirtualFile(
        override val uri: URI,
    ) : VirtualFile {

        override val name: String = uri.path.substringAfterLast('/', missingDelimiterValue = uri.toString())
        override val path: String = uri.path
        override val parentUri: URI? = run {
            val p = uri.path
            val idx = p.lastIndexOf('/')
            if (idx <= 0) null else URI("${uri.scheme}://${uri.authority}${p.substring(0, idx)}")
        }

        override suspend fun exists(): Boolean = withContext(Dispatchers.IO) {
            val conn = open(uri, "HEAD")
            try {
                conn.connect()
                conn.responseCode == HttpURLConnection.HTTP_OK
            } finally {
                conn.disconnect()
            }
        }

        override suspend fun isDirectory(): Boolean = false
        override suspend fun isFile(): Boolean = true

        override suspend fun length(): Long = withContext(Dispatchers.IO) {
            val conn = open(uri, "HEAD")
            try {
                conn.connect()
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext 0L
                conn.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L
            } finally {
                conn.disconnect()
            }
        }

        override suspend fun lastModified(): Long = withContext(Dispatchers.IO) {
            val conn = open(uri, "HEAD")
            try {
                conn.connect()
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext 0L
                val v = conn.getHeaderField("Last-Modified") ?: return@withContext 0L
                runCatching {
                    parseHttpLastModifiedToEpochMillis(v)
                }.getOrDefault(0L)
            } finally {
                conn.disconnect()
            }
        }

        override suspend fun listFiles() = throw UnsupportedOperationException("HTTP provider does not support listFiles")

        override suspend fun openInputStream(): InputStream = withContext(Dispatchers.IO) {
            val conn = open(uri, "GET")
            conn.connect()
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                throw FileNotFoundException("GET $uri failed: HTTP $code")
            }
            object : FilterInputStream(conn.inputStream) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        conn.disconnect()
                    }
                }
            }
        }

        override suspend fun openOutputStream(append: Boolean): OutputStream = withContext(Dispatchers.IO) {
            val buffer = ByteArrayOutputStream()
            if (append) {
                runCatching { openInputStream().use { buffer.write(it.readBytes()) } }
            }
            object : FilterOutputStream(buffer) {
                override fun close() {
                    super.close()
                    val bytes = buffer.toByteArray()
                    val conn = open(uri, "PUT").apply {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/octet-stream")
                        setRequestProperty("Content-Length", bytes.size.toString())
                    }
                    try {
                        conn.connect()
                        conn.outputStream.use { it.write(bytes) }
                        val code = conn.responseCode
                        if (code !in listOf(
                                HttpURLConnection.HTTP_OK,
                                HttpURLConnection.HTTP_CREATED,
                                HttpURLConnection.HTTP_NO_CONTENT,
                            )
                        ) {
                            throw java.io.IOException("PUT $uri failed: HTTP $code")
                        }
                    } finally {
                        conn.disconnect()
                    }
                }
            }
        }
    }
}
