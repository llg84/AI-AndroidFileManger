package com.example.filemanager

import com.example.filemanager.data.local.LocalFileSystemProvider
import com.example.filemanager.data.vfs.VfsManager
import com.example.filemanager.domain.vfs.ChunkedOpenMode
import java.io.File
import java.net.URI
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VfsManagerLocalTest {
    @get:Rule
    val tempDir = TemporaryFolder()

    private fun createManager(): VfsManager {
        val local = LocalFileSystemProvider()
        return VfsManager(providers = mapOf(local.scheme to local))
    }

    @Test
    fun providerFor_unknownScheme_throws() {
        val m = createManager()
        try {
            m.providerFor(URI("ftp://example.com/path"))
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun local_createRenameListCopy_delete_and_chunkedIO() = runTest {
        val m = createManager()

        val root = tempDir.newFolder("root")
        val rootUri = root.toURI()

        // create directory + file
        val childDir = m.createChildDirectory(rootUri, "dir")
        assertTrue(childDir.isDirectory())

        val childFile = m.createChildFile(childDir.uri, "a.txt")
        childFile.openOutputStream(append = false).use { it.write("hello".toByteArray()) }
        assertEquals(5L, childFile.length())

        // list
        val children = m.listChildren(childDir.uri)
        assertEquals(listOf("a.txt"), children.map { it.name })

        // rename
        val renamed = m.renameTo(childFile.uri, "b.txt")
        assertTrue(renamed)
        assertFalse(File(childFile.uri).exists())
        val renamedFile = File(File(childDir.uri), "b.txt")
        assertTrue(renamedFile.exists())

        // copy
        val srcVf = m.getFile(renamedFile.toURI())
        val destVf = m.createChildFile(childDir.uri, "copy.txt")
        m.copy(srcVf, destVf)
        val copiedBytes = destVf.openInputStream().use { it.readBytes() }
        assertEquals("hello", String(copiedBytes))

        // chunked random access
        val chunked = m.openChunked(destVf.uri, ChunkedOpenMode.READ_WRITE)
        try {
            val data = "XY".toByteArray()
            chunked.write(position = 1, buffer = data, offset = 0, length = data.size)

            val buf = ByteArray(5)
            val read = chunked.read(position = 0, buffer = buf, offset = 0, length = buf.size)
            assertTrue(read > 0)
            assertArrayEquals("hXYlo".toByteArray(), buf)

            chunked.setLength(2)
            assertEquals(2L, chunked.length())
        } finally {
            chunked.close()
        }

        // delete dir recursively
        val deleted = m.delete(childDir.uri)
        assertTrue(deleted)
        assertFalse(File(childDir.uri).exists())
    }
}
