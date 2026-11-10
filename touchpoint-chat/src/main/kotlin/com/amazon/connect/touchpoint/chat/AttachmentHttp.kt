package com.amazon.connect.touchpoint.chat

import com.amazon.connect.touchpoint.core.AttachmentUpload
import com.amazon.connect.touchpoint.core.TouchpointException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * A file's bytes, either way: streamed up to the address Connect gave for it, with the headers it
 * asked for, or down from the one it gives for an attachment. Platform HTTP, TLS by the system's
 * trust, as every other call (ADR 0001); the addresses are signed, so they are never logged.
 */
internal object AttachmentHttp {
    fun interface Opener {
        fun open(url: URL): HttpURLConnection
    }

    private val platform = Opener { it.openConnection() as? HttpURLConnection ?: throw TouchpointException.TransportFailure("upload address is not HTTP") }

    suspend fun put(
        address: String,
        headers: Map<String, String>,
        file: AttachmentUpload,
        opener: Opener = platform,
        requireHttps: Boolean = true,
    ) {
        val url =
            try {
                URL(address)
            } catch (_: IOException) {
                throw TouchpointException.TransportFailure("upload address is malformed")
            }
        if (requireHttps && url.protocol != "https") throw TouchpointException.TransportFailure("upload address is not HTTPS")
        runInterruptible(Dispatchers.IO) {
            val connection = opener.open(url)
            try {
                connection.requestMethod = "PUT"
                connection.doOutput = true
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.instanceFollowRedirects = false
                // Exactly the size declared to Connect: a file that grew since is refused, not truncated.
                connection.setFixedLengthStreamingMode(file.sizeBytes)
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                try {
                    file.open().use { input -> connection.outputStream.use { input.copyTo(it) } }
                    val status = connection.responseCode
                    if (status !in 200..299) throw TouchpointException.TransportFailure("upload status $status")
                } catch (e: IOException) {
                    // Interrupted by our own cancellation: that, not a failure; runInterruptible makes it one.
                    if (Thread.interrupted()) throw InterruptedException()
                    throw TouchpointException.TransportFailure("upload ${e.javaClass.simpleName}")
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    /** Stream the file at [address] into [out], which the caller closes. */
    suspend fun get(
        address: String,
        out: java.io.OutputStream,
        opener: Opener = platform,
        requireHttps: Boolean = true,
    ) {
        val url =
            try {
                URL(address)
            } catch (_: IOException) {
                throw TouchpointException.TransportFailure("download address is malformed")
            }
        if (requireHttps && url.protocol != "https") throw TouchpointException.TransportFailure("download address is not HTTPS")
        runInterruptible(Dispatchers.IO) {
            val connection = opener.open(url)
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.instanceFollowRedirects = false
                try {
                    val status = connection.responseCode
                    if (status !in 200..299) throw TouchpointException.TransportFailure("download status $status")
                    connection.inputStream.use { it.copyTo(out) }
                } catch (e: IOException) {
                    // Interrupted by our own cancellation: that, not a failure; runInterruptible makes it one.
                    if (Thread.interrupted()) throw InterruptedException()
                    throw TouchpointException.TransportFailure("download ${e.javaClass.simpleName}")
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
}
