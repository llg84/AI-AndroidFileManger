package com.example.filemanager.data.saf

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.example.filemanager.domain.vfs.ChunkedOpenMode
import com.example.filemanager.domain.vfs.ChunkedRandomAccess
import com.example.filemanager.domain.vfs.FileSystemProvider
import com.example.filemanager.domain.vfs.VirtualFile
import java.io.FileNotFoundException
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

/**
 * SAF Provider：用于 content:// URI（DocumentFile）。
 *
 * 说明：SAF 不适合用“路径 + URI”方式做 createFile/createDirectory，因此这两个 API 在当前版本不暴露。
 * 创建子文件/子目录请通过 [com.example.filemanager.data.vfs.VfsManager.createChildFile]/createChildDirectory。
 */
class SafFileSystemProvider(
    private val context: Context,
) : FileSystemProvider {

    override val scheme: String = SCHEME

    override suspend fun getFile(uri: URI): VirtualFile {
        require(uri.scheme == scheme) { "Unsupported scheme: ${uri.scheme}" }
        val androidUri = Uri.parse(uri.toString())
        val doc = resolveDocumentFile(androidUri)
            ?: throw FileNotFoundException("Cannot resolve DocumentFile: $androidUri")
        return SafVirtualFile(context, doc, androidUri)
    }

    override suspend fun createFile(uri: URI): VirtualFile {
        throw UnsupportedOperationException(
            "SAF createFile(uri) is not supported. Use VfsManager.createChildFile(parentDirUri, name).",
        )
    }

    override suspend fun createDirectory(uri: URI): VirtualFile {
        throw UnsupportedOperationException(
            "SAF createDirectory(uri) is not supported. Use VfsManager.createChildDirectory(parentDirUri, name).",
        )
    }

    override suspend fun delete(uri: URI): Boolean {
        val vf = getFile(uri) as SafVirtualFile
        return withContext(Dispatchers.IO) {
            // DocumentFile.delete() 对不存在目标会返回 false；为了“幂等删除”，将不存在视为成功。
            if (!vf.documentFile.exists()) true else vf.documentFile.delete()
        }
    }

    override suspend fun rename(sourceUri: URI, destUri: URI): Boolean {
        // SAF 的 renameTo 只接受新名称；destUri 在此实现中无意义。
        val vf = getFile(sourceUri) as SafVirtualFile
        val newName = Uri.parse(destUri.toString()).lastPathSegment
        require(!newName.isNullOrBlank()) { "destUri must contain new name as lastPathSegment" }
        return withContext(Dispatchers.IO) {
            vf.documentFile.renameTo(newName)
        }
    }

    override suspend fun copy(
        sourceFile: VirtualFile,
        destFile: VirtualFile,
        progressListener: (bytesCopied: Long) -> Unit,
        cancellationSignal: Job?,
        bufferSize: Int,
    ) {
        // 直接沿用 FileSystemProvider 契约（流式桥接）。
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
        throw UnsupportedOperationException("SAF chunked access is not supported in current version")
    }

    private fun resolveDocumentFile(uri: Uri): DocumentFile? {
        return if (DocumentsContract.isTreeUri(uri)) {
            DocumentFile.fromTreeUri(context, uri)
        } else {
            DocumentFile.fromSingleUri(context, uri)
        }
    }

    companion object {
        const val SCHEME: String = "content"
    }
}

