package com.example.filemanager.domain.vfs

import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import kotlinx.coroutines.flow.Flow

/**
 * VFS 的统一文件/目录抽象。
 * Domain 层不依赖 Android SDK，因此使用 [URI] 和标准 I/O。
 */
interface VirtualFile {
    val uri: URI
    val name: String
    val path: String
    val parentUri: URI?

    suspend fun exists(): Boolean
    suspend fun isDirectory(): Boolean
    suspend fun isFile(): Boolean
    suspend fun length(): Long
    suspend fun lastModified(): Long

    /**
     * 仅当当前对象为目录时有意义；非目录可抛出异常。
     */
    suspend fun listFiles(): Flow<VirtualFile>

    /**
     * 仅当当前对象为文件时有意义；目录可抛出异常。
     */
    suspend fun openInputStream(): InputStream

    /**
     * 仅当当前对象为文件时有意义；目录可抛出异常。
     */
    suspend fun openOutputStream(append: Boolean = false): OutputStream
}

