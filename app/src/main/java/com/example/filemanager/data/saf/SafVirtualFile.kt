package com.example.filemanager.data.saf

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.filemanager.domain.vfs.VirtualFile
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * SAF (DocumentFile) 的 VirtualFile 实现。
 *
 * 注意：SAF 的“父目录”概念并不总是可逆/可构造，这里 parentUri 可能为 null。
 */
class SafVirtualFile internal constructor(
    val context: Context,
    val documentFile: DocumentFile,
    private val rawUri: Uri = documentFile.uri,
) : VirtualFile {

    override val uri: URI = URI(rawUri.toString())
    override val name: String = documentFile.name ?: rawUri.lastPathSegment.orEmpty()
    override val path: String = rawUri.toString()
    override val parentUri: URI? = null

    override suspend fun exists(): Boolean = withContext(Dispatchers.IO) { documentFile.exists() }

    override suspend fun isDirectory(): Boolean = withContext(Dispatchers.IO) { documentFile.isDirectory }

    override suspend fun isFile(): Boolean = withContext(Dispatchers.IO) { documentFile.isFile }

    override suspend fun length(): Long = withContext(Dispatchers.IO) { documentFile.length() }

    override suspend fun lastModified(): Long = withContext(Dispatchers.IO) { documentFile.lastModified() }

    override suspend fun listFiles(): Flow<VirtualFile> {
        return flow {
            val children = documentFile.listFiles().sortedBy { it.name ?: "" }
            for (child in children) {
                emit(SafVirtualFile(context, child))
            }
        }.flowOn(Dispatchers.IO)
    }

    override suspend fun openInputStream(): InputStream {
        return withContext(Dispatchers.IO) {
            val input = context.contentResolver.openInputStream(rawUri)
            input ?: throw FileNotFoundException("Cannot open input stream: $rawUri")
        }
    }

    override suspend fun openOutputStream(append: Boolean): OutputStream {
        return withContext(Dispatchers.IO) {
            // SAF 仅支持 "w"/"wa" 两种模式。
            val mode = if (append) "wa" else "w"
            val output = context.contentResolver.openOutputStream(rawUri, mode)
            output ?: throw FileNotFoundException("Cannot open output stream: $rawUri")
        }
    }
}

