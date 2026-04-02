package com.example.filemanager.data.smb

import com.example.filemanager.domain.vfs.ChunkedOpenMode
import com.example.filemanager.domain.vfs.ChunkedRandomAccess
import com.example.filemanager.domain.vfs.FileSystemProvider
import com.example.filemanager.domain.vfs.VirtualFile
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import jcifs.CIFSContext
import jcifs.context.SingletonContext
import jcifs.smb.NtlmPasswordAuthentication
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileInputStream
import jcifs.smb.SmbFileOutputStream

/**
 * SMB Provider：处理 smb:// scheme（局域网共享读写）。
 *
 * 约定：
 * - 认证信息来自 URI 的 userInfo：
 *   - "user:password" 或 "domain;user:password"（domain 可选）
 *   - 缺省使用匿名/空凭证
 * - 为了便于纯 JVM 单测，本实现通过 [SmbClient] 注入底层实现，测试可对其进行 Mock。
 */
internal class SmbFileSystemProvider(
    private val smbClient: SmbClient = JcifsSmbClient(),
) : FileSystemProvider {
    override val scheme: String = SCHEME

    override suspend fun getFile(uri: URI): VirtualFile {
        require(uri.scheme == scheme) { "Unsupported scheme: ${uri.scheme}" }
        return SmbVirtualFile(uri = uri, smbClient = smbClient, auth = parseAuth(uri.userInfo))
    }

    override suspend fun createFile(uri: URI): VirtualFile {
        require(uri.scheme == scheme) { "Unsupported scheme: ${uri.scheme}" }
        return withContext(Dispatchers.IO) {
            val auth = parseAuth(uri.userInfo)
            val f = smbClient.resolve(uri, auth)
            if (!f.exists()) {
                // 确保父目录存在（尽力而为）
                f.parent()?.mkdirs()
                f.createNewFile()
            }
            getFile(uri)
        }
    }

    override suspend fun createDirectory(uri: URI): VirtualFile {
        require(uri.scheme == scheme) { "Unsupported scheme: ${uri.scheme}" }
        return withContext(Dispatchers.IO) {
            val auth = parseAuth(uri.userInfo)
            smbClient.resolve(uri, auth).mkdirs()
            getFile(uri)
        }
    }

    override suspend fun delete(uri: URI): Boolean {
        require(uri.scheme == scheme) { "Unsupported scheme: ${uri.scheme}" }
        return withContext(Dispatchers.IO) {
            val auth = parseAuth(uri.userInfo)
            val f = smbClient.resolve(uri, auth)
            if (!f.exists()) return@withContext true
            deleteRecursive(f)
            true
        }
    }

    override suspend fun rename(sourceUri: URI, destUri: URI): Boolean {
        require(sourceUri.scheme == scheme) { "Unsupported scheme: ${sourceUri.scheme}" }
        require(destUri.scheme == scheme) { "Unsupported scheme: ${destUri.scheme}" }
        return withContext(Dispatchers.IO) {
            val auth = parseAuth(sourceUri.userInfo)
            val src = smbClient.resolve(sourceUri, auth)
            val dest = smbClient.resolve(destUri, auth)
            dest.parent()?.mkdirs()
            src.renameTo(dest)
            true
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
        throw UnsupportedOperationException("SMB provider does not support chunked access")
    }

    private fun deleteRecursive(f: SmbResource) {
        if (!f.exists()) return
        if (f.isDirectory()) {
            for (c in f.listFiles()) {
                deleteRecursive(c)
            }
        }
        f.delete()
    }

    private class SmbVirtualFile(
        override val uri: URI,
        private val smbClient: SmbClient,
        private val auth: SmbAuth,
    ) : VirtualFile {
        override val name: String = run {
            val p = uri.path.orEmpty().trimEnd('/')
            if (p.isBlank() || p == "/") uri.host ?: "/" else p.substringAfterLast('/')
        }

        override val path: String = uri.path.orEmpty()

        override val parentUri: URI? = run {
            val p = uri.path.orEmpty().trimEnd('/')
            if (p.isBlank() || p == "/") return@run null
            val parentPath = p.substringBeforeLast('/', missingDelimiterValue = "/").ifBlank { "/" } + "/"
            URI(uri.scheme, uri.userInfo, uri.host, uri.port, parentPath, null, null)
        }

        private fun resolve(): SmbResource = smbClient.resolve(uri, auth)

        override suspend fun exists(): Boolean = withContext(Dispatchers.IO) { resolve().exists() }
        override suspend fun isDirectory(): Boolean = withContext(Dispatchers.IO) { resolve().isDirectory() }
        override suspend fun isFile(): Boolean = withContext(Dispatchers.IO) { resolve().isFile() }
        override suspend fun length(): Long = withContext(Dispatchers.IO) { resolve().length() }
        override suspend fun lastModified(): Long = withContext(Dispatchers.IO) { resolve().lastModified() }

        override suspend fun listFiles(): Flow<VirtualFile> {
            return flow {
                val children = resolve().listFiles().sortedBy { it.uri.path.orEmpty() }
                for (c in children) {
                    emit(SmbVirtualFile(uri = c.uri, smbClient = smbClient, auth = auth))
                }
            }.flowOn(Dispatchers.IO)
        }

        override suspend fun openInputStream(): InputStream = withContext(Dispatchers.IO) {
            val r = resolve()
            if (!r.exists() || !r.isFile()) throw FileNotFoundException("SMB file not found: $uri")
            r.openInputStream()
        }

        override suspend fun openOutputStream(append: Boolean): OutputStream = withContext(Dispatchers.IO) {
            val r = resolve()
            r.parent()?.mkdirs()
            r.openOutputStream(append)
        }
    }

    internal data class SmbAuth(
        val domain: String = "",
        val username: String = "",
        val password: String = "",
    )

    internal interface SmbClient {
        fun resolve(uri: URI, auth: SmbAuth): SmbResource
    }

    internal interface SmbResource {
        val uri: URI

        fun parent(): SmbResource?
        fun exists(): Boolean
        fun isDirectory(): Boolean
        fun isFile(): Boolean
        fun length(): Long
        fun lastModified(): Long

        fun listFiles(): List<SmbResource>
        fun openInputStream(): InputStream
        fun openOutputStream(append: Boolean): OutputStream

        fun createNewFile()
        fun mkdirs()
        fun delete()
        fun renameTo(dest: SmbResource)
    }

    internal class JcifsSmbClient : SmbClient {
        // SingletonContext 初始化时会触发 DNS/网络相关逻辑；若在主线程构造会被 StrictMode 拦截
        // （Firebase Test Lab / Robo 默认开启严格模式，直接导致 NetworkOnMainThreadException）。
        // 延迟到首次 resolve 时再初始化，并确保调用方在 Dispatchers.IO 上执行。
        private val baseContext: CIFSContext by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            SingletonContext.getInstance()
        }

        override fun resolve(uri: URI, auth: SmbAuth): SmbResource {
            val ctx = baseContext.withCredentials(
                NtlmPasswordAuthentication(baseContext, auth.domain, auth.username, auth.password),
            )
            // jcifs-ng 对目录 URL 通常要求以 '/' 结尾，这里保留原样，由调用方控制。
            val smbFile = SmbFile(uri.toString(), ctx)
            return JcifsSmbResource(smbFile, auth, this)
        }
    }

    internal class JcifsSmbResource(
        private val file: SmbFile,
        private val auth: SmbAuth,
        private val client: JcifsSmbClient,
    ) : SmbResource {

        override val uri: URI = file.url.toURI()

        override fun parent(): SmbResource? {
            val p = uri.path.orEmpty().trimEnd('/')
            if (p.isBlank() || p == "/") return null
            val parentPath = p.substringBeforeLast('/', missingDelimiterValue = "/").ifBlank { "/" } + "/"
            val parentUri = URI(uri.scheme, uri.userInfo, uri.host, uri.port, parentPath, null, null)
            return client.resolve(parentUri, auth)
        }

        override fun exists(): Boolean = file.exists()
        override fun isDirectory(): Boolean = file.isDirectory
        override fun isFile(): Boolean = file.isFile
        override fun length(): Long = file.length()
        override fun lastModified(): Long = file.lastModified()

        override fun listFiles(): List<SmbResource> = file.listFiles()?.map { JcifsSmbResource(it, auth, client) }.orEmpty()

        override fun openInputStream(): InputStream = SmbFileInputStream(file)
        override fun openOutputStream(append: Boolean): OutputStream = SmbFileOutputStream(file, append)

        override fun createNewFile() {
            file.createNewFile()
        }

        override fun mkdirs() {
            file.mkdirs()
        }

        override fun delete() {
            file.delete()
        }

        override fun renameTo(dest: SmbResource) {
            val d = dest as? JcifsSmbResource
                ?: throw IllegalArgumentException("dest must be JcifsSmbResource")
            file.renameTo(d.file)
        }
    }

    companion object {
        const val SCHEME: String = "smb"

        internal fun parseAuth(userInfo: String?): SmbAuth {
            if (userInfo.isNullOrBlank()) return SmbAuth()
            // domain;user:pass
            val domainPart = userInfo.substringBefore(';', missingDelimiterValue = "")
            val rest = if (userInfo.contains(';')) userInfo.substringAfter(';') else userInfo
            val user = rest.substringBefore(':', missingDelimiterValue = rest)
            val pass = rest.substringAfter(':', missingDelimiterValue = "")
            return SmbAuth(domain = domainPart, username = user, password = pass)
        }
    }
}
