package com.example.filemanager.data.local

import com.example.filemanager.domain.vfs.VirtualFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

internal class LocalVirtualFile(
    private val file: File,
) : VirtualFile {

    override val uri: URI = file.toURI()
    override val name: String = file.name.ifBlank { file.path }
    override val path: String = file.absolutePath
    override val parentUri: URI? = file.parentFile?.toURI()

    override suspend fun exists(): Boolean = withContext(Dispatchers.IO) { file.exists() }

    override suspend fun isDirectory(): Boolean = withContext(Dispatchers.IO) { file.isDirectory }

    override suspend fun isFile(): Boolean = withContext(Dispatchers.IO) { file.isFile }

    override suspend fun length(): Long = withContext(Dispatchers.IO) { file.length() }

    override suspend fun lastModified(): Long = withContext(Dispatchers.IO) { file.lastModified() }

    override suspend fun listFiles(): Flow<VirtualFile> {
        return flow {
            val children = file.listFiles()?.toList().orEmpty().sortedBy { it.name }
            for (child in children) {
                emit(LocalVirtualFile(child))
            }
        }.flowOn(Dispatchers.IO)
    }

    override suspend fun openInputStream(): InputStream {
        return withContext(Dispatchers.IO) {
            FileInputStream(file)
        }
    }

    override suspend fun openOutputStream(append: Boolean): OutputStream {
        return withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            FileOutputStream(file, append)
        }
    }
}

