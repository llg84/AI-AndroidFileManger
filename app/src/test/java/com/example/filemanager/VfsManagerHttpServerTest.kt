package com.example.filemanager

import com.example.filemanager.data.http.HttpFileSystemProvider
import com.example.filemanager.data.local.LocalFileSystemProvider
import com.example.filemanager.data.vfs.VfsManager
import java.io.Closeable
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VfsManagerHttpServerTest {
    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun http_remoteReadWrite_and_remoteToLocalCopy() = runTest {
        InMemoryMockWebServer().use { server ->
            val local = LocalFileSystemProvider()
            val http = HttpFileSystemProvider("http")
            val m = VfsManager(providers = mapOf(local.scheme to local, http.scheme to http))

            val remoteUri = server.uri("/remote/hello.txt")

            // 1) 远端写入（PUT）+ 远端读取（GET）
            val remoteVf = m.getFile(remoteUri)
            remoteVf.openOutputStream(append = false).use { it.write("hello".toByteArray()) }
            assertTrue(remoteVf.exists())
            assertEquals(5L, remoteVf.length())
            val remoteBytes = remoteVf.openInputStream().use { it.readBytes() }
            assertEquals("hello", String(remoteBytes))

            // 2) 远端 -> 本地拷贝
            val localRoot = tempDir.newFolder("localRoot")
            val localFile = m.createChildFile(localRoot.toURI(), "from_remote.txt")
            m.copy(sourceFile = remoteVf, destFile = localFile)
            val localBytes = localFile.openInputStream().use { it.readBytes() }
            assertEquals("hello", String(localBytes))

            // 3) 本地 -> 远端拷贝（覆盖写）
            val uploadLocal = m.createChildFile(localRoot.toURI(), "upload.txt")
            uploadLocal.openOutputStream(append = false).use { it.write("upload".toByteArray()) }
            val uploadRemote = m.getFile(server.uri("/remote/upload.txt"))
            m.copy(sourceFile = uploadLocal, destFile = uploadRemote)
            val uploadRemoteBytes = uploadRemote.openInputStream().use { it.readBytes() }
            assertEquals("upload", String(uploadRemoteBytes))
        }
    }
}

private class InMemoryMockWebServer : Closeable {
    private val store = ConcurrentHashMap<String, ByteArray>()
    private val server = MockWebServer()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return try {
                    handle(request)
                } catch (t: Throwable) {
                    MockResponse()
                        .setResponseCode(500)
                        .setHeader("Content-Type", "text/plain")
                        .setBody(t.message ?: t.toString())
                }
            }
        }
        server.start()
    }

    fun uri(path: String): URI {
        require(path.startsWith("/")) { "path must start with '/': $path" }
        return URI.create(server.url(path).toString())
    }

    private fun handle(request: RecordedRequest): MockResponse {
        val path = request.requestUrl?.encodedPath ?: request.path?.substringBefore('?') ?: "/"
        return when (request.method?.uppercase()) {
            "PUT" -> {
                val bytes = request.body.readByteArray()
                store[path] = bytes
                MockResponse().setResponseCode(200)
            }
            "GET" -> {
                val bytes = store[path] ?: return MockResponse().setResponseCode(404)
                val body = Buffer().write(bytes)
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/octet-stream")
                    .setHeader("Content-Length", bytes.size)
                    .setBody(body)
            }
            "HEAD" -> {
                val bytes = store[path] ?: return MockResponse().setResponseCode(404)
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Length", bytes.size)
            }
            "DELETE" -> {
                val existed = store.remove(path) != null
                MockResponse().setResponseCode(if (existed) 204 else 404)
            }
            else -> MockResponse().setResponseCode(405)
        }
    }

    override fun close() {
        server.shutdown()
        store.clear()
    }
}
