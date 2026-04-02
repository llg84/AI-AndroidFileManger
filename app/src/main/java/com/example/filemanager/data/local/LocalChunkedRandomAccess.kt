package com.example.filemanager.data.local

import com.example.filemanager.domain.vfs.ChunkedOpenMode
import com.example.filemanager.domain.vfs.ChunkedRandomAccess
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class LocalChunkedRandomAccess(
    file: File,
    mode: ChunkedOpenMode,
) : ChunkedRandomAccess {
    private val raf = RandomAccessFile(
        file,
        when (mode) {
            ChunkedOpenMode.READ -> "r"
            ChunkedOpenMode.WRITE, ChunkedOpenMode.READ_WRITE -> "rw"
        },
    )

    override suspend fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        return withContext(Dispatchers.IO) {
            raf.seek(position)
            raf.read(buffer, offset, length)
        }
    }

    override suspend fun write(position: Long, buffer: ByteArray, offset: Int, length: Int) {
        withContext(Dispatchers.IO) {
            raf.seek(position)
            raf.write(buffer, offset, length)
        }
    }

    override suspend fun length(): Long = withContext(Dispatchers.IO) { raf.length() }

    override suspend fun setLength(newLength: Long) {
        withContext(Dispatchers.IO) {
            raf.setLength(newLength)
        }
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) {
            raf.close()
        }
    }
}

