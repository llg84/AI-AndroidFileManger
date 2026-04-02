package com.example.filemanager

import com.example.filemanager.data.local.LocalFileSystemProvider
import com.example.filemanager.data.smb.SmbFileSystemProvider
import com.example.filemanager.data.vfs.VfsManager
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SmbFileSystemProviderTest {
    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun smb_mock_list_read_write_and_remoteToLocalCopy() = runTest {
        val smbClient = mockk<SmbFileSystemProvider.SmbClient>()

        val dirUri = URI.create("smb://user:pass@server/share/dir/")
        val fileUri = URI.create("smb://user:pass@server/share/dir/hello.txt")
        val uploadUri = URI.create("smb://user:pass@server/share/dir/upload.txt")

        val dirRes = mockk<SmbFileSystemProvider.SmbResource>()
        val fileRes = mockk<SmbFileSystemProvider.SmbResource>()
        val uploadRes = mockk<SmbFileSystemProvider.SmbResource>()

        // dir resource
        every { dirRes.uri } returns dirUri
        every { dirRes.exists() } returns true
        every { dirRes.isDirectory() } returns true
        every { dirRes.isFile() } returns false
        every { dirRes.length() } returns 0L
        every { dirRes.lastModified() } returns 0L
        every { dirRes.parent() } returns null
        every { dirRes.mkdirs() } just runs
        every { dirRes.delete() } just runs
        every { dirRes.renameTo(any()) } just runs

        // existing file resource
        every { fileRes.uri } returns fileUri
        every { fileRes.exists() } returns true
        every { fileRes.isDirectory() } returns false
        every { fileRes.isFile() } returns true
        every { fileRes.length() } returns 5L
        every { fileRes.lastModified() } returns 0L
        every { fileRes.parent() } returns dirRes
        every { fileRes.listFiles() } returns emptyList()
        every { fileRes.delete() } just runs
        every { fileRes.renameTo(any()) } just runs
        every { fileRes.createNewFile() } just runs
        every { fileRes.openInputStream() } answers { ByteArrayInputStream("hello".toByteArray()) }
        every { fileRes.openOutputStream(any()) } answers { ByteArrayOutputStream() }

        // upload file resource (created lazily)
        val uploadBytes = ByteArrayOutputStream()
        every { uploadRes.uri } returns uploadUri
        every { uploadRes.exists() } returnsMany listOf(false, true, true, true)
        every { uploadRes.isDirectory() } returns false
        every { uploadRes.isFile() } returns true
        every { uploadRes.length() } answers { uploadBytes.size().toLong() }
        every { uploadRes.lastModified() } returns 0L
        every { uploadRes.parent() } returns dirRes
        every { uploadRes.listFiles() } returns emptyList()
        every { uploadRes.createNewFile() } just runs
        every { uploadRes.delete() } just runs
        every { uploadRes.renameTo(any()) } just runs
        every { uploadRes.openOutputStream(any()) } answers { uploadBytes }
        every { uploadRes.openInputStream() } answers { ByteArrayInputStream(uploadBytes.toByteArray()) }

        // directory lists one existing file
        every { dirRes.listFiles() } returns listOf(fileRes)

        // client resolve routing
        every { smbClient.resolve(dirUri, any()) } returns dirRes
        every { smbClient.resolve(fileUri, any()) } returns fileRes
        every { smbClient.resolve(uploadUri, any()) } returns uploadRes

        val local = LocalFileSystemProvider()
        val smb = SmbFileSystemProvider(smbClient)
        val m = VfsManager(providers = mapOf(local.scheme to local, smb.scheme to smb))

        // 1) list
        val children = m.listChildren(dirUri)
        assertEquals(listOf("hello.txt"), children.map { it.name })

        // 2) read
        val remote = m.getFile(fileUri)
        assertTrue(remote.exists())
        assertEquals("hello", String(remote.openInputStream().use { it.readBytes() }))

        // 3) create + write + read back
        val created = m.createChildFile(dirUri, "upload.txt")
        created.openOutputStream(append = false).use { it.write("upload".toByteArray()) }
        assertEquals("upload", String(created.openInputStream().use { it.readBytes() }))

        // 4) remote -> local copy
        val localRoot = tempDir.newFolder("localRoot")
        val localFile = m.createChildFile(localRoot.toURI(), "from_smb.txt")
        m.copy(sourceFile = created, destFile = localFile)
        assertEquals("upload", String(localFile.openInputStream().use { it.readBytes() }))
    }
}

