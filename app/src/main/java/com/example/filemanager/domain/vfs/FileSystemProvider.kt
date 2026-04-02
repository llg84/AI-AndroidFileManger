package com.example.filemanager.domain.vfs

import java.net.URI
import kotlinx.coroutines.Job

/**
 * 文件系统提供者：按 scheme 区分（file/smb/ftp/...）。
 * 当前阶段仅实现本地 file scheme 的 Provider。
 */
interface FileSystemProvider {
    val scheme: String

    suspend fun getFile(uri: URI): VirtualFile

    suspend fun createFile(uri: URI): VirtualFile
    suspend fun createDirectory(uri: URI): VirtualFile
    suspend fun delete(uri: URI): Boolean
    suspend fun rename(sourceUri: URI, destUri: URI): Boolean

    /**
     * 跨协议复制的基础能力：以流的方式搬运数据。
     * 当前阶段实现为通用的 InputStream -> OutputStream 桥接。
     */
    suspend fun copy(
        sourceFile: VirtualFile,
        destFile: VirtualFile,
        progressListener: (bytesCopied: Long) -> Unit = {},
        cancellationSignal: Job? = null,
        bufferSize: Int = DEFAULT_BUFFER_SIZE,
    )

    /**
     * 断点续传/分块读写的底层抽象。
     */
    suspend fun openChunked(uri: URI, mode: ChunkedOpenMode): ChunkedRandomAccess
}

enum class ChunkedOpenMode {
    READ,
    WRITE,
    READ_WRITE,
}

interface ChunkedRandomAccess {
    suspend fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
    suspend fun write(position: Long, buffer: ByteArray, offset: Int, length: Int)
    suspend fun length(): Long
    suspend fun setLength(newLength: Long)
    suspend fun close()
}

