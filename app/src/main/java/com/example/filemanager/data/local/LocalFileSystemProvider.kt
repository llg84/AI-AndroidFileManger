package com.example.filemanager.data.local

import com.example.filemanager.domain.vfs.ChunkedOpenMode
import com.example.filemanager.domain.vfs.ChunkedRandomAccess
import com.example.filemanager.domain.vfs.FileSystemProvider
import com.example.filemanager.domain.vfs.VirtualFile
import java.io.File
import java.net.URI
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

class LocalFileSystemProvider : FileSystemProvider {
    override val scheme: String = "file"

    override suspend fun getFile(uri: URI): VirtualFile {
        require(uri.scheme == scheme) { "Unsupported scheme: ${uri.scheme}" }
        return LocalVirtualFile(File(uri))
    }

    override suspend fun createFile(uri: URI): VirtualFile {
        require(uri.scheme == scheme) { "Unsupported scheme: ${uri.scheme}" }
        return withContext(Dispatchers.IO) {
            val target = File(uri)
            target.parentFile?.mkdirs()
            if (!target.exists()) {
                target.createNewFile()
            }
            LocalVirtualFile(target)
        }
    }

    override suspend fun createDirectory(uri: URI): VirtualFile {
        require(uri.scheme == scheme) { "Unsupported scheme: ${uri.scheme}" }
        return withContext(Dispatchers.IO) {
            val dir = File(uri)
            dir.mkdirs()
            LocalVirtualFile(dir)
        }
    }

    override suspend fun delete(uri: URI): Boolean {
        require(uri.scheme == scheme) { "Unsupported scheme: ${uri.scheme}" }
        return withContext(Dispatchers.IO) {
            val target = File(uri)
            if (!target.exists()) return@withContext true
            if (target.isDirectory) target.deleteRecursively() else target.delete()
        }
    }

    override suspend fun rename(sourceUri: URI, destUri: URI): Boolean {
        require(sourceUri.scheme == scheme) { "Unsupported scheme: ${sourceUri.scheme}" }
        require(destUri.scheme == scheme) { "Unsupported scheme: ${destUri.scheme}" }
        return withContext(Dispatchers.IO) {
            val src = File(sourceUri)
            val dest = File(destUri)
            dest.parentFile?.mkdirs()
            src.renameTo(dest)
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
                        if (cancellationSignal?.isCancelled == true) {
                            throw CancellationException("copy cancelled")
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
        require(uri.scheme == scheme) { "Unsupported scheme: ${uri.scheme}" }
        val file = File(uri)
        return withContext(Dispatchers.IO) {
            LocalChunkedRandomAccess(file, mode)
        }
    }
}
