package com.example.filemanager.data.ftp

import android.util.Log
import com.example.filemanager.domain.vfs.ChunkedOpenMode
import com.example.filemanager.domain.vfs.ChunkedRandomAccess
import com.example.filemanager.domain.vfs.FileSystemProvider
import com.example.filemanager.domain.vfs.VirtualFile
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintWriter
import java.net.URI
import java.net.SocketException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.apache.commons.net.PrintCommandListener
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply

/**
 * FTP Provider：处理 ftp:// scheme。
 *
 * 约定：
 * - 认证信息来自 URI 的 userInfo（形如 user:password）。缺省为匿名登录。
 * - 每次操作创建独立连接（避免在 VirtualFile 上持久化连接状态）；流式读写会在流 close 时关闭连接。
 */
internal class FtpFileSystemProvider(
    private val clientFactory: FtpClientFactory = DefaultFtpClientFactory(),
) : FileSystemProvider {
    override val scheme: String = SCHEME

    override suspend fun getFile(uri: URI): VirtualFile {
        require(uri.scheme == scheme) { "Unsupported scheme: ${uri.scheme}" }
        return FtpVirtualFile(uri = uri, clientFactory = clientFactory)
    }

    override suspend fun createFile(uri: URI): VirtualFile {
        require(uri.scheme == scheme) { "Unsupported scheme: ${uri.scheme}" }
        return withContext(Dispatchers.IO) {
            clientFactory.open(uri).use { session ->
                val path = ftpPath(uri)
                // 幂等：若已存在则直接返回
                if (!session.exists(path)) {
                    val ok = session.client.storeFile(path, ByteArrayInputStream(ByteArray(0)))
                    if (!ok) throw IOException("FTP createFile failed: $uri")
                }
            }
            getFile(uri)
        }
    }

    override suspend fun createDirectory(uri: URI): VirtualFile {
        require(uri.scheme == scheme) { "Unsupported scheme: ${uri.scheme}" }
        return withContext(Dispatchers.IO) {
            clientFactory.open(uri).use { session ->
                val path = ftpPath(uri)
                // 逐级创建：父目录不存在时 makeDirectory 可能失败
                val segments = path.trim('/').split('/').filter { it.isNotBlank() }
                var current = ""
                for (seg in segments) {
                    current += "/$seg"
                    session.client.makeDirectory(current)
                }
            }
            getFile(uri)
        }
    }

    override suspend fun delete(uri: URI): Boolean {
        require(uri.scheme == scheme) { "Unsupported scheme: ${uri.scheme}" }
        return withContext(Dispatchers.IO) {
            clientFactory.open(uri).use { session ->
                val path = ftpPath(uri)
                if (!session.exists(path)) return@withContext true
                session.deleteRecursive(path)
                true
            }
        }
    }

    override suspend fun rename(sourceUri: URI, destUri: URI): Boolean {
        require(sourceUri.scheme == scheme) { "Unsupported scheme: ${sourceUri.scheme}" }
        require(destUri.scheme == scheme) { "Unsupported scheme: ${destUri.scheme}" }
        return withContext(Dispatchers.IO) {
            clientFactory.open(sourceUri).use { session ->
                session.client.rename(ftpPath(sourceUri), ftpPath(destUri))
            }
        }
    }

    override suspend fun copy(
        sourceFile: VirtualFile,
        destFile: VirtualFile,
        progressListener: (bytesCopied: Long) -> Unit,
        cancellationSignal: Job?,
        bufferSize: Int,
    ) {
        withContext(Dispatchers.IO) {
            sourceFile.openInputStream().use { input ->
                destFile.openOutputStream(append = false).use { output ->
                    val buffer = ByteArray(bufferSize)
                    var total = 0L
                    while (true) {
                        if (cancellationSignal?.isCancelled == true) throw CancellationException("copy cancelled")
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
        throw UnsupportedOperationException("FTP provider does not support chunked access")
    }

    private class FtpVirtualFile(
        override val uri: URI,
        private val clientFactory: FtpClientFactory,
    ) : VirtualFile {
        private val ftpPath: String = ftpPath(uri)

        override val name: String = run {
            val p = ftpPath.trimEnd('/')
            if (p.isBlank() || p == "/") uri.host ?: "/" else p.substringAfterLast('/')
        }
        override val path: String = ftpPath
        override val parentUri: URI? = run {
            val p = ftpPath.trimEnd('/').ifBlank { "/" }
            if (p == "/") return@run null
            val parent = p.substringBeforeLast('/', missingDelimiterValue = "/").ifBlank { "/" }
            buildUri(uri, parent)
        }

        override suspend fun exists(): Boolean = withContext(Dispatchers.IO) {
            clientFactory.open(uri).use { it.exists(ftpPath) }
        }

        override suspend fun isDirectory(): Boolean = withContext(Dispatchers.IO) {
            clientFactory.open(uri).use { it.isDirectory(ftpPath) }
        }

        override suspend fun isFile(): Boolean = withContext(Dispatchers.IO) {
            clientFactory.open(uri).use { it.isFile(ftpPath) }
        }

        override suspend fun length(): Long = withContext(Dispatchers.IO) {
            clientFactory.open(uri).use { session ->
                if (session.isDirectory(ftpPath)) return@withContext 0L
                session.statFile(ftpPath)?.size ?: 0L
            }
        }

        override suspend fun lastModified(): Long = withContext(Dispatchers.IO) {
            clientFactory.open(uri).use { session ->
                if (session.isDirectory(ftpPath)) return@withContext 0L
                session.statFile(ftpPath)?.timestamp?.timeInMillis ?: 0L
            }
        }

        override suspend fun listFiles(): Flow<VirtualFile> {
            return flow {
                clientFactory.open(uri).use { session ->
                    val files = session.client.listFiles(ftpPath).orEmpty()
                        .filter { it.name != null && it.name != "." && it.name != ".." }
                        .sortedBy { it.name }
                    val base = ftpPath.trimEnd('/').ifBlank { "/" }
                    for (f in files) {
                        val childPath = if (base == "/") "/${f.name}" else "$base/${f.name}"
                        emit(FtpVirtualFile(uri = buildUri(uri, childPath), clientFactory = clientFactory))
                    }
                }
            }.flowOn(Dispatchers.IO)
        }

        override suspend fun openInputStream(): InputStream = withContext(Dispatchers.IO) {
            val session = clientFactory.open(uri)
            try {
                val stream = session.client.retrieveFileStream(ftpPath)
                    ?: throw FileNotFoundException("FTP retrieveFileStream returned null: $uri")
                object : FilterInputStream(stream) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            // 必须 completePendingCommand，否则连接状态不一致
                            runCatching { session.client.completePendingCommand() }
                            session.close()
                        }
                    }
                }
            } catch (t: Throwable) {
                session.close()
                throw t
            }
        }

        override suspend fun openOutputStream(append: Boolean): OutputStream = withContext(Dispatchers.IO) {
            val session = clientFactory.open(uri)
            try {
                val raw = if (append) {
                    session.client.appendFileStream(ftpPath)
                } else {
                    session.client.storeFileStream(ftpPath)
                } ?: throw IOException("FTP openOutputStream returned null: $uri")

                object : FilterOutputStream(raw) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            runCatching { session.client.completePendingCommand() }
                            session.close()
                        }
                    }
                }
            } catch (t: Throwable) {
                session.close()
                throw t
            }
        }
    }

    internal interface FtpClientFactory {
        fun open(uri: URI): FtpSession
    }

    internal class DefaultFtpClientFactory : FtpClientFactory {
        override fun open(uri: URI): FtpSession {
            val host = uri.host ?: throw IllegalArgumentException("FTP URI missing host: $uri")
            val port = if (uri.port > 0) uri.port else 21
            // 注意：java.net.URI 的 userInfo 可能是 decode 后的；为排查 `@` / `%xx` 等字符导致的截断，优先取 rawUserInfo。
            val rawUserInfo = uri.rawUserInfo ?: uri.userInfo
            val (user, pass) = parseUserInfo(rawUserInfo)
            val client = FTPClient().apply {
                connectTimeout = 5_000
                defaultTimeout = 5_000
            }

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                // 仅在本地测试场景输出明文密码，避免误把敏感信息打到线上日志。
                val passForLog = if (host == "127.0.0.1" || host == "localhost") pass else "***len=${pass.length}"
                Log.d(
                    TAG,
                    "FTP userInfo parsed: raw=$rawUserInfo decoded=${uri.userInfo} user=$user pass=$passForLog uri=${redactUri(uri)}",
                )
            }

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "FTP connect: host=$host port=$port user=$user uri=${redactUri(uri)}")
            }

            // 打印底层 FTP 交互（USER/PASS/PWD/... 以及服务端 reply）。
            // 注意：会包含明文密码，仅用于问题定位；如需关闭可通过移除此 listener 或在此处加开关。
            client.addProtocolCommandListener(
                PrintCommandListener(
                    PrintWriter(
                        object : java.io.OutputStream() {
                            override fun write(b: Int) {}
                            override fun write(b: ByteArray, off: Int, len: Int) {
                                val msg = String(b, off, len).trim()
                                if (msg.isNotEmpty()) {
                                    Log.d("FTP_TRAFFIC", msg)
                                }
                            }
                        },
                    ),
                    true,
                ),
            )

            try {
                client.connect(host, port)
            } catch (e: SocketException) {
                val ex = FtpConnectRuntimeException(
                    buildString {
                        append("FTP connect SocketException: host=")
                        append(host)
                        append(" port=")
                        append(port)
                        append(" uri=")
                        append(redactUri(uri))
                        if (host == "127.0.0.1" || host == "localhost") {
                            append(" (本地调试：真机访问 127.0.0.1 需要 adb reverse；例如 adb reverse tcp:2121 tcp:2121。也请确认 AndroidManifest 配置了 usesCleartextTraffic=true)")
                        }
                    },
                    e,
                )
                Log.e(TAG, ex.message ?: "FTP connect SocketException", ex)
                throw ex
            } catch (e: IOException) {
                val ex = FtpConnectRuntimeException(
                    buildString {
                        append("FTP connect IOException: host=")
                        append(host)
                        append(" port=")
                        append(port)
                        append(" uri=")
                        append(redactUri(uri))
                        if (host == "127.0.0.1" || host == "localhost") {
                            append(" (本地调试：真机访问 127.0.0.1 需要 adb reverse；例如 adb reverse tcp:2121 tcp:2121。也请确认 AndroidManifest 配置了 usesCleartextTraffic=true)")
                        }
                    },
                    e,
                )
                Log.e(TAG, ex.message ?: "FTP connect IOException", ex)
                throw ex
            }
            client.soTimeout = 5_000

            // 连接成功也需要检查欢迎语 reply code
            val connectCode = client.replyCode
            if (!FTPReply.isPositiveCompletion(connectCode)) {
                val reply = client.replyString?.trim().orEmpty()
                runCatching { client.disconnect() }
                throw IOException("FTP connect failed: code=$connectCode reply=$reply uri=${redactUri(uri)}")
            }

            // 默认启用 PASV：本地调试/adb reverse 场景更容易工作
            client.enterLocalPassiveMode()
            // PASV 响应的 IP 在 NAT/端口转发下可能不正确；关闭校验避免连接被拒
            client.isRemoteVerificationEnabled = false

            val loggedIn = runCatching { client.login(user, pass) }.getOrElse { t ->
                val code = client.replyCode
                val reply = client.replyString?.trim().orEmpty()
                runCatching { client.disconnect() }
                throw IOException(
                    "FTP login exception: code=$code reply=$reply user=$user uri=${redactUri(uri)}",
                    t,
                )
            }
            if (!loggedIn) {
                val code = client.replyCode
                val reply = client.replyString?.trim().orEmpty()
                runCatching { client.disconnect() }
                // 将 code/reply 放在前面：即便 UI/日志截断，也更容易看到服务端真实报错（例如 530）。
                throw IOException("FTP login failed: code=$code reply=$reply user=$user uri=${redactUri(uri)}")
            }

            client.setFileType(FTP.BINARY_FILE_TYPE)
            return FtpSession(client)
        }
    }

    internal class FtpSession(
        val client: FTPClient,
    ) : AutoCloseable {

        fun exists(path: String): Boolean {
            if (path == "/") return true
            // 1) mlistFile 更准确
            val m = runCatching { client.mlistFile(path) }.getOrNull()
            if (m != null) return true
            // 2) 目录（含空目录）用 CWD 判断
            if (runCatching { client.changeWorkingDirectory(path) }.getOrDefault(false)) return true
            // 3) 文件：listFiles(path) 通常返回单元素数组
            val list = runCatching { client.listFiles(path) }.getOrNull().orEmpty()
            return list.isNotEmpty()
        }

        fun isDirectory(path: String): Boolean {
            if (path == "/") return true
            return runCatching { client.changeWorkingDirectory(path) }.getOrDefault(false)
        }

        fun isFile(path: String): Boolean {
            if (!exists(path)) return false
            if (isDirectory(path)) return false
            return true
        }

        fun statFile(path: String): FTPFile? {
            val m = runCatching { client.mlistFile(path) }.getOrNull()
            if (m != null) return m
            val list = runCatching { client.listFiles(path) }.getOrNull().orEmpty()
            return list.firstOrNull()
        }

        fun deleteRecursive(path: String) {
            if (!exists(path)) return
            if (isDirectory(path)) {
                val children = client.listFiles(path).orEmpty()
                    .filter { it.name != null && it.name != "." && it.name != ".." }
                val base = path.trimEnd('/').ifBlank { "/" }
                for (c in children) {
                    val childPath = if (base == "/") "/${c.name}" else "$base/${c.name}"
                    deleteRecursive(childPath)
                }
                // 最后删除目录本身
                client.removeDirectory(path)
            } else {
                client.deleteFile(path)
            }
        }

        override fun close() {
            runCatching { client.logout() }
            runCatching { client.disconnect() }
        }
    }

    companion object {
        const val SCHEME: String = "ftp"

        private const val TAG: String = "FtpFileSystemProvider"

        private fun ftpPath(uri: URI): String {
            val p = uri.path.orEmpty()
            val normalized = if (p.isBlank()) "/" else p
            return if (normalized.startsWith('/')) normalized else "/$normalized"
        }

        private fun buildUri(base: URI, path: String): URI {
            val normalized = if (path.startsWith('/')) path else "/$path"
            return URI(base.scheme, base.userInfo, base.host, base.port, normalized, null, null)
        }

        private fun parseUserInfo(rawUserInfo: String?): Pair<String, String> {
            if (rawUserInfo.isNullOrBlank()) return "anonymous" to "anonymous"

            // 这里必须按“最多切两段”的方式拆分：user:password（password 里允许继续包含 ':'）。
            // 同时保留 rawUserInfo（通常是 percent-encoded），再做 decode。
            val parts = rawUserInfo.split(":", limit = 2)
            val rawUser = parts.getOrNull(0).orEmpty()
            val rawPass = parts.getOrNull(1).orEmpty()
            return decodeUserInfoPart(rawUser) to decodeUserInfoPart(rawPass)
        }

        private fun decodeUserInfoPart(raw: String): String {
            if (raw.isBlank()) return raw
            // URLDecoder 会把 '+' 解释为空格，但在 URI userInfo 中 '+' 是合法字面量。
            // 为避免误伤，先将 '+' 保护起来。
            val safe = raw.replace("+", "%2B")
            return runCatching { URLDecoder.decode(safe, StandardCharsets.UTF_8.name()) }
                .getOrDefault(raw)
        }

        private fun redactUri(uri: URI): String {
            val userInfo = uri.userInfo
            val redactedUserInfo = when {
                userInfo.isNullOrBlank() -> null
                !userInfo.contains(':') -> userInfo
                else -> userInfo.substringBefore(':') + ":***"
            }
            return URI(uri.scheme, redactedUserInfo, uri.host, uri.port, uri.path, null, null).toString()
        }
    }

    private class FtpConnectRuntimeException(
        message: String,
        cause: Throwable,
    ) : RuntimeException(message, cause)
}
