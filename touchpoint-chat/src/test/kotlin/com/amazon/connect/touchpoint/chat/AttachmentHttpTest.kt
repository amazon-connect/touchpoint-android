package com.amazon.connect.touchpoint.chat

import com.amazon.connect.touchpoint.core.AttachmentUpload
import com.amazon.connect.touchpoint.core.TouchpointException
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The file's bytes against a local server: what goes up, with which headers, and what comes down. */
class AttachmentHttpTest {
    private fun server(status: Int, body: ByteArray = ByteArray(0), seen: MutableList<Pair<Map<String, String>, ByteArray>> = mutableListOf()): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
                seen += headers to ex.requestBody.readBytes()
                ex.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
                if (body.isNotEmpty()) ex.responseBody.use { it.write(body) }
                ex.close()
            }
            start()
        }

    private fun HttpServer.address() = "http://127.0.0.1:${address.port}/upload"

    @Test
    fun `a put streams exactly the file with Connect's headers, and a refusal fails it`() =
        runBlocking {
            val seen = mutableListOf<Pair<Map<String, String>, ByteArray>>()
            val ok = server(200, seen = seen)
            try {
                val bytes = byteArrayOf(1, 2, 3, 4)
                AttachmentHttp.put(ok.address(), mapOf("x-amz-meta-test" to "yes"), AttachmentUpload("a.png", "image/png", 4) { ByteArrayInputStream(bytes) }, requireHttps = false)
                assertContentEquals(bytes, seen.single().second)
                assertEquals("yes", seen.single().first["x-amz-meta-test"])
            } finally {
                ok.stop(0)
            }
            val refused = server(403)
            try {
                val e = assertFailsWith<TouchpointException.TransportFailure> {
                    AttachmentHttp.put(refused.address(), emptyMap(), AttachmentUpload("a.png", "image/png", 1) { ByteArrayInputStream(byteArrayOf(9)) }, requireHttps = false)
                }
                assertEquals("upload status 403", e.detail) // the address never in an error
            } finally {
                refused.stop(0)
            }
        }

    @Test
    fun `a file larger than declared is refused, not truncated, and plain HTTP is refused outright`() =
        runBlocking {
            val ok = server(200)
            try {
                assertFailsWith<TouchpointException.TransportFailure> {
                    AttachmentHttp.put(ok.address(), emptyMap(), AttachmentUpload("a.png", "image/png", 2) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }, requireHttps = false)
                }
                assertFailsWith<TouchpointException.TransportFailure> {
                    AttachmentHttp.put(ok.address(), emptyMap(), AttachmentUpload("a.png", "image/png", 1) { ByteArrayInputStream(byteArrayOf(1)) })
                }
            } finally {
                ok.stop(0)
            }
        }

    @Test
    fun `a get streams the file down`() =
        runBlocking {
            val file = ByteArray(70_000) { (it % 251).toByte() }
            val ok = server(200, body = file)
            try {
                val out = ByteArrayOutputStream()
                AttachmentHttp.get(ok.address(), out, requireHttps = false)
                assertContentEquals(file, out.toByteArray())
            } finally {
                ok.stop(0)
            }
        }

    @Test
    fun `cancelling an upload is cancellation, not a failure`() =
        runBlocking {
            val ok = server(200)
            try {
                val slow = object : java.io.InputStream() {
                    override fun read(): Int {
                        Thread.sleep(50)
                        return 1
                    }
                }
                var thrown: Throwable? = null
                val job =
                    launch(kotlinx.coroutines.Dispatchers.Default) {
                        try {
                            AttachmentHttp.put(ok.address(), emptyMap(), AttachmentUpload("a.bin", "application/octet-stream", 1_000_000) { slow }, requireHttps = false)
                        } catch (t: Throwable) {
                            thrown = t
                            throw t
                        }
                    }
                kotlinx.coroutines.delay(300)
                job.cancel()
                job.join()
                kotlin.test.assertTrue(thrown is kotlinx.coroutines.CancellationException, "cancelled, not failed: ${thrown?.javaClass}")
            } finally {
                ok.stop(0)
            }
        }
}
