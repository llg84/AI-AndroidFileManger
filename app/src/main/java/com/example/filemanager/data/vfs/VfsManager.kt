package com.example.filemanager.data.vfs

import android.content.Context
import android.webkit.MimeTypeMap
import com.example.filemanager.data.ftp.FtpFileSystemProvider
import com.example.filemanager.data.local.LocalFileSystemProvider
import com.example.filemanager.data.saf.SafFileSystemProvider
import com.example.filemanager.data.saf.SafVirtualFile
import com.example.filemanager.data.smb.SmbFileSystemProvider
import com.example.filemanager.domain.vfs.ChunkedOpenMode
import com.example.filemanager.domain.vfs.ChunkedRandomAccess
import com.example.filemanager.domain.vfs.FileSystemProvider
import com.example.filemanager.domain.vfs.VirtualFile
import java.io.File
import java.net.URI
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext

/**
 * VFS 统一入口：按 URI scheme 将请求路由给不同的 Provider。
 *
 * 目前实现：
 * - file:// -> [LocalFileSystemProvider]
 * - content:// -> [SafFileSystemProvider]（SAF）
 * - ftp:// -> [FtpFileSystemProvider]
 * - smb:// -> [SmbFileSystemProvider]
 */
class VfsManager(
    private val providers: Map<String, FileSystemProvider>,
) {
    fun providerFor(uri: URI): FileSystemProvider {
        val scheme = uri.scheme ?: throw IllegalArgumentException("URI missing scheme: $uri")
        return providers[scheme] ?: throw IllegalArgumentException("Unsupported scheme: $scheme")
    }

    suspend fun getFile(uri: URI): VirtualFile = providerFor(uri).getFile(uri)

    suspend fun delete(uri: URI): Boolean = providerFor(uri).delete(uri)

    /**
     * 按“新名称”重命名：
     * - file:// 通过 renameTo(父目录 + 新名称)
     * - content:// 通过 SAF 的 DocumentFile.renameTo
     */
    suspend fun renameTo(sourceUri: URI, newName: String): Boolean {
        require(newName.isNotBlank()) { "newName is blank" }
        return when (sourceUri.scheme) {
            LOCAL_SCHEME -> {
                val src = File(sourceUri)
                val dest = File(src.parentFile, newName)
                providerFor(sourceUri).rename(src.toURI(), dest.toURI())
            }
            SafFileSystemProvider.SCHEME -> {
                withContext(Dispatchers.IO) {
                    val vf = getFile(sourceUri)
                    val saf = vf as? SafVirtualFile
                        ?: throw IllegalStateException("Expected SafVirtualFile for uri=$sourceUri")
                    saf.documentFile.renameTo(newName)
                }
            }
            else -> {
                val destUri = buildSiblingUri(sourceUri, newName)
                providerFor(sourceUri).rename(sourceUri, destUri)
            }
        }
    }

    /**
     * 在目录下创建子目录。
     */
    suspend fun createChildDirectory(parentDirUri: URI, displayName: String): VirtualFile {
        require(displayName.isNotBlank()) { "displayName is blank" }
        return when (parentDirUri.scheme) {
            LOCAL_SCHEME -> {
                val dir = File(parentDirUri)
                val child = File(dir, displayName)
                providerFor(parentDirUri).createDirectory(child.toURI())
            }
            SafFileSystemProvider.SCHEME -> {
                withContext(Dispatchers.IO) {
                    val parent = getFile(parentDirUri) as? SafVirtualFile
                        ?: throw IllegalStateException("Expected SafVirtualFile for uri=$parentDirUri")
                    val created = parent.documentFile.createDirectory(displayName)
                        ?: throw IllegalStateException("Failed to create directory: $displayName")
                    SafVirtualFile(parent.context, created)
                }
            }
            else -> {
                val childUri = buildChildUri(parentDirUri, displayName, isDirectory = true)
                providerFor(parentDirUri).createDirectory(childUri)
            }
        }
    }

    /**
     * 在目录下创建子文件。
     */
    suspend fun createChildFile(parentDirUri: URI, displayName: String, mimeType: String? = null): VirtualFile {
        require(displayName.isNotBlank()) { "displayName is blank" }
        return when (parentDirUri.scheme) {
            LOCAL_SCHEME -> {
                val dir = File(parentDirUri)
                val child = File(dir, displayName)
                providerFor(parentDirUri).createFile(child.toURI())
            }
            SafFileSystemProvider.SCHEME -> {
                withContext(Dispatchers.IO) {
                    val parent = getFile(parentDirUri) as? SafVirtualFile
                        ?: throw IllegalStateException("Expected SafVirtualFile for uri=$parentDirUri")
                    val resolvedMime = mimeType ?: guessMimeType(displayName)
                    val created = parent.documentFile.createFile(resolvedMime, displayName)
                        ?: throw IllegalStateException("Failed to create file: $displayName")
                    SafVirtualFile(parent.context, created)
                }
            }
            else -> {
                val childUri = buildChildUri(parentDirUri, displayName, isDirectory = false)
                providerFor(parentDirUri).createFile(childUri)
            }
        }
    }

    /**
     * 通用流式复制：适用于跨 scheme。
     */
    suspend fun copy(
        sourceFile: VirtualFile,
        destFile: VirtualFile,
        progressListener: (bytesCopied: Long) -> Unit = {},
        cancellationSignal: Job? = null,
        bufferSize: Int = DEFAULT_BUFFER_SIZE,
    ) {
        // 使用源文件 scheme 对应 provider（实现上都是 stream bridge；此处保持接口一致）。
        providerFor(sourceFile.uri).copy(
            sourceFile = sourceFile,
            destFile = destFile,
            progressListener = progressListener,
            cancellationSignal = cancellationSignal,
            bufferSize = bufferSize,
        )
    }

    suspend fun openChunked(uri: URI, mode: ChunkedOpenMode): ChunkedRandomAccess =
        providerFor(uri).openChunked(uri, mode)

    suspend fun listChildren(dirUri: URI): List<VirtualFile> {
        val dir = getFile(dirUri)
        if (!dir.isDirectory()) return emptyList()
        return dir.listFiles().toList()
    }

    companion object {
        private const val LOCAL_SCHEME: String = "file"

        fun createDefault(context: Context): VfsManager {
            val local = LocalFileSystemProvider()
            val saf = SafFileSystemProvider(context.applicationContext)
            val ftp = FtpFileSystemProvider()
            val smb = SmbFileSystemProvider()
            return VfsManager(
                providers = mapOf(
                    local.scheme to local,
                    saf.scheme to saf,
                    ftp.scheme to ftp,
                    smb.scheme to smb,
                ),
            )
        }

        private fun buildChildUri(parentDirUri: URI, displayName: String, isDirectory: Boolean): URI {
            val base = parentDirUri.path.orEmpty().ifBlank { "/" }
            val baseDir = if (base.endsWith('/')) base else "$base/"
            val path = baseDir + displayName + if (isDirectory) "/" else ""
            return URI(parentDirUri.scheme, parentDirUri.userInfo, parentDirUri.host, parentDirUri.port, path, null, null)
        }

        private fun buildSiblingUri(sourceUri: URI, newName: String): URI {
            val p = sourceUri.path.orEmpty().trimEnd('/')
            val parent = p.substringBeforeLast('/', missingDelimiterValue = "/").ifBlank { "/" }
            val path = if (parent.endsWith('/')) "$parent$newName" else "$parent/$newName"
            return URI(sourceUri.scheme, sourceUri.userInfo, sourceUri.host, sourceUri.port, path, null, null)
        }

        private fun guessMimeType(displayName: String): String {
            val ext = displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            if (ext.isBlank()) return "application/octet-stream"
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: "application/octet-stream"
        }
    }
}
