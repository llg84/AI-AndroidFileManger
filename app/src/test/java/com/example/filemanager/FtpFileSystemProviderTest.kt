package com.example.filemanager

import com.example.filemanager.data.ftp.FtpFileSystemProvider
import com.example.filemanager.data.local.LocalFileSystemProvider
import com.example.filemanager.data.vfs.VfsManager
import java.net.ServerSocket
import java.net.URI
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockftpserver.fake.FakeFtpServer
import org.mockftpserver.fake.UserAccount
import org.mockftpserver.fake.filesystem.DirectoryEntry
import org.mockftpserver.fake.filesystem.FileEntry
import org.mockftpserver.fake.filesystem.UnixFakeFileSystem

class FtpFileSystemProviderTest {
    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun ftp_list_read_write_and_remoteToLocalCopy() = runTest {
        val port = ServerSocket(0).use { it.localPort }
        val server = FakeFtpServer().apply {
            serverControlPort = port
            addUserAccount(UserAccount("user", "pass", "/"))

            val fs = UnixFakeFileSystem().apply {
                add(DirectoryEntry("/"))
                add(DirectoryEntry("/dir"))
                add(FileEntry("/dir/hello.txt", "hello"))
            }
            fileSystem = fs
        }

        try {
            server.start()

            val local = LocalFileSystemProvider()
            val ftp = FtpFileSystemProvider()
            val m = VfsManager(providers = mapOf(local.scheme to local, ftp.scheme to ftp))

            val base = "ftp://user:pass@127.0.0.1:$port"
            val dirUri = URI.create("$base/dir")
            val helloUri = URI.create("$base/dir/hello.txt")

            // 1) list
            val children = m.listChildren(dirUri)
            assertEquals(listOf("hello.txt"), children.map { it.name })

            // 2) read
            val hello = m.getFile(helloUri)
            assertTrue(hello.exists())
            assertEquals(5L, hello.length())
            assertEquals("hello", String(hello.openInputStream().use { it.readBytes() }))

            // 3) write + read back
            val uploadUri = URI.create("$base/dir/upload.txt")
            val upload = m.getFile(uploadUri)
            upload.openOutputStream(append = false).use { it.write("upload".toByteArray()) }
            assertTrue(upload.exists())
            assertEquals("upload", String(upload.openInputStream().use { it.readBytes() }))

            // 4) remote -> local copy
            val localRoot = tempDir.newFolder("localRoot")
            val localFile = m.createChildFile(localRoot.toURI(), "from_ftp.txt")
            m.copy(sourceFile = upload, destFile = localFile)
            assertEquals("upload", String(localFile.openInputStream().use { it.readBytes() }))
        } finally {
            runCatching { server.stop() }
        }
    }
}

