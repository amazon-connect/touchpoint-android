package com.amazon.connect.touchpoint.chat

import com.amazon.connect.chat.sdk.ChatSession
import com.amazon.connect.chat.sdk.model.ConnectionDetails
import com.amazon.connect.chat.sdk.model.ContentType
import com.amazon.connect.chat.sdk.model.Event
import com.amazon.connect.chat.sdk.model.Message
import com.amazon.connect.chat.sdk.model.TranscriptItem
import com.amazon.connect.chat.sdk.network.AWSClient
import com.amazon.connect.chat.sdk.provider.ConnectionDetailsProvider
import com.amazon.connect.touchpoint.core.ReceiptKind
import com.amazon.connect.touchpoint.core.TouchpointException
import aws.sdk.kotlin.services.connectparticipant.model.ChatItemType
import aws.sdk.kotlin.services.connectparticipant.model.GetTranscriptRequest
import aws.sdk.kotlin.services.connectparticipant.model.GetTranscriptResponse
import aws.sdk.kotlin.services.connectparticipant.model.ParticipantRole
import com.amazon.connect.chat.sdk.model.Region
import aws.sdk.kotlin.services.connectparticipant.model.Item
import aws.sdk.kotlin.services.connectparticipant.model.SendEventResponse
import aws.sdk.kotlin.services.connectparticipant.model.SendMessageResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.lang.reflect.Proxy
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one class that touches the SDK, against a fake of it: the SDK's session is an interface and
 * its client an open class, so neither needs a device.
 */
class ConnectChatSessionDriverTest {
    private class RecordingClient : AWSClient() {
        val events: MutableList<Pair<ContentType, String>> = Collections.synchronizedList(mutableListOf())
        val sent: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val sentTypes: MutableList<ContentType> = Collections.synchronizedList(mutableListOf())
        var transcript: Result<GetTranscriptResponse> = Result.success(GetTranscriptResponse {})
        var sendFailure: Throwable? = null

        override suspend fun sendEvent(connectionToken: String, contentType: ContentType, content: String): Result<SendEventResponse> {
            events += contentType to content
            return Result.success(SendEventResponse {})
        }

        override suspend fun sendMessage(connectionToken: String, contentType: ContentType, message: String): Result<SendMessageResponse> {
            sendFailure?.let { return Result.failure(it) }
            sent += message
            sentTypes += contentType
            return Result.success(SendMessageResponse { id = "srv-1"; absoluteTime = "2026-10-08T14:00:01.123Z" })
        }

        override suspend fun getTranscript(request: GetTranscriptRequest): Result<GetTranscriptResponse> = transcript

        val uploads: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var uploadAddress = "https://upload.invalid/x"

        override suspend fun startAttachmentUpload(
            connectionToken: String,
            request: aws.sdk.kotlin.services.connectparticipant.model.StartAttachmentUploadRequest,
        ): Result<aws.sdk.kotlin.services.connectparticipant.model.StartAttachmentUploadResponse> {
            uploads += "start:${request.attachmentName}:${request.contentType}:${request.attachmentSizeInBytes}:${request.clientToken != null}"
            return Result.success(
                aws.sdk.kotlin.services.connectparticipant.model.StartAttachmentUploadResponse {
                    attachmentId = "att-1"
                    uploadMetadata = aws.sdk.kotlin.services.connectparticipant.model.UploadMetadata { url = uploadAddress; headersToInclude = mapOf("x-amz-meta" to "1") }
                },
            )
        }

        override suspend fun completeAttachmentUpload(
            connectionToken: String,
            request: aws.sdk.kotlin.services.connectparticipant.model.CompleteAttachmentUploadRequest,
        ): Result<aws.sdk.kotlin.services.connectparticipant.model.CompleteAttachmentUploadResponse> {
            uploads += "complete:${request.attachmentIds}"
            return Result.success(aws.sdk.kotlin.services.connectparticipant.model.CompleteAttachmentUploadResponse {})
        }

        override suspend fun getAttachment(
            connectionToken: String,
            attachmentId: String,
        ): Result<aws.sdk.kotlin.services.connectparticipant.model.GetAttachmentResponse> =
            Result.success(aws.sdk.kotlin.services.connectparticipant.model.GetAttachmentResponse { url = "https://download.invalid/$attachmentId"; attachmentSizeInBytes = 1024 })
    }

    /** The fake upstream session: records setters and calls; `disconnect` fails on demand. */
    private class FakeSession {
        val captured = mutableMapOf<String, Any?>()
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        /**
         * What `disconnect` returns, in the form a proxy must hand back: a suspend function returning
         * `Result` passes it unboxed, so a failure is the bare internal failure object.
         */
        var disconnectFailure: Throwable? = null

        private fun unboxedFailure(e: Throwable): Any =
            Class.forName("kotlin.Result\$Failure").declaredConstructors.single().apply { isAccessible = true }.newInstance(e)

        @Suppress("UNCHECKED_CAST")
        private fun <T> proxy(type: Class<T>, handler: (String, Array<Any?>?) -> Any?): T =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, m, args -> handler(m.name, args) } as T

        val session: ChatSession =
            proxy(ChatSession::class.java) { mangled, args ->
                // A function returning `Result` has a mangled JVM name (`disconnect-IoAF18A`).
                val name = mangled.substringBefore('-')
                calls += name
                when {
                    name == "getConnectionDetailsProvider" ->
                        proxy(ConnectionDetailsProvider::class.java) { n, _ -> if (n == "getConnectionDetails") ConnectionDetails("wss://x", "ctok", "2026-12-01T00:00:00Z") else null }
                    name.startsWith("set") && args?.size == 1 -> captured[name] = args[0]
                    name == "connect" -> true
                    name == "disconnect" -> disconnectFailure?.let(::unboxedFailure) ?: true
                    name == "reset" -> true
                    else -> null
                }
            }

        @Suppress("UNCHECKED_CAST")
        fun messageCallback() = captured["setOnMessageReceived"] as (TranscriptItem) -> Unit

        @Suppress("UNCHECKED_CAST")
        fun typingCallback() = captured["setOnTyping"] as (com.amazon.connect.chat.sdk.model.Event?) -> Unit
    }

    private fun driver(fake: FakeSession = FakeSession(), client: AWSClient = RecordingClient()) = ConnectChatSessionDriver(fake.session, client, Region.CA_CENTRAL_1)

    private fun collecting(d: ConnectChatSessionDriver): MutableList<RawTranscriptItem> =
        Collections.synchronizedList(mutableListOf<RawTranscriptItem>()).also { items ->
            d.setHandlers(ChatDriverHandlers({}, {}, {}, {}, { items += it }, { _, _ -> }))
        }

    private fun frame(vararg fields: Pair<String, Any>): String =
        fields.joinToString(",", "{", "}") { (k, v) -> "\"$k\":" + if (v is String) "\"$v\"" else v.toString() }

    @Test
    fun `a live frame yields the participant id from the raw frame, and our own message is marked outgoing`() =
        runBlocking {
            val fake = FakeSession()
            val d = driver(fake)
            val items = collecting(d)
            d.connect("c", "p-me", "tok")
            val agent = frame("Id" to "m1", "ParticipantRole" to "AGENT", "ParticipantId" to "p-ag", "Type" to "MESSAGE")
            fake.messageCallback()(Message(id = "m1", timeStamp = "t", contentType = "text/plain", serializedContent = agent, participant = "AGENT", text = "hi"))
            val mine = frame("Id" to "m2", "ParticipantRole" to "CUSTOMER", "ParticipantId" to "p-me", "Type" to "MESSAGE")
            fake.messageCallback()(Message(id = "m2", timeStamp = "t", contentType = "text/plain", serializedContent = mine, participant = "CUSTOMER", text = "me"))
            assertEquals("p-ag", items[0].participantId)
            assertFalse(items[0].isOutgoing)
            assertTrue(items[1].isOutgoing)
        }

    @Test
    fun `a row of the SDK's history replay is marked as such`() =
        runBlocking {
            val fake = FakeSession()
            val d = driver(fake)
            val items = collecting(d)
            d.connect("c", "p-me", "tok")
            val history = frame("Id" to "u1", "ParticipantRole" to "SUPERVISOR", "Type" to "MESSAGE", "isFromPastSession" to true)
            fake.messageCallback()(Message(id = "u1", timeStamp = "t", contentType = "text/plain", serializedContent = history, participant = "SUPERVISOR", text = "hi"))
            val joined = frame("Id" to "e1", "ParticipantRole" to "AGENT", "Type" to "EVENT", "isFromPastSession" to true)
            fake.messageCallback()(Event(participant = "AGENT", timeStamp = "t", contentType = ConnectContentType.JOINED, id = "e1", serializedContent = joined))
            assertTrue(items.all { it.isFromPastSession })
            assertNull(items[1].participantId) // the replay drops it: the transport ignores such a join
        }

    @Test
    fun `a deep-heartbeat failure is not mapped - its broken connection is reported once`() {
        val fake = FakeSession()
        collecting(driver(fake))
        assertFalse("setOnDeepHeartBeatFailure" in fake.captured)
        assertTrue("setOnConnectionBroken" in fake.captured)
        assertTrue("setOnConnectionReEstablished" in fake.captured)
    }

    @Test
    fun `typing - the customer's goes as the SDK's event, and everyone else's is reported but our own echo`() =
        runBlocking {
            val client = RecordingClient()
            val fake = FakeSession()
            val d = driver(fake, client)
            d.sendTyping()
            assertEquals(ContentType.TYPING to "", client.events.single())
            val roles = Collections.synchronizedList(mutableListOf<String>())
            d.setHandlers(ChatDriverHandlers({}, {}, {}, {}, {}, { _, _ -> }, onTyping = { roles += it }))
            fun typing(role: String?) =
                com.amazon.connect.chat.sdk.model.Event(
                    participant = role, timeStamp = "2026-10-08T14:00:00.000Z", contentType = "application/vnd.amazonaws.connect.event.typing", id = "e1",
                )
            fake.typingCallback()(typing("AGENT"))
            fake.typingCallback()(typing("CUSTOMER"))
            fake.typingCallback()(typing(null))
            fake.typingCallback()(null)
            assertEquals(listOf("AGENT"), roles.toList())
        }

    @Test
    fun `receipts are well-formed JSON whatever the id holds`() =
        runBlocking {
            val client = RecordingClient()
            val d = driver(client = client)
            d.sendReceipt(ReceiptKind.READ, "abc\\\"x")
            val (type, content) = client.events.single()
            assertEquals(ContentType.MESSAGE_READ, type)
            assertEquals("abc\\\"x", Json.parseToJsonElement(content).jsonObject.getValue("messageId").jsonPrimitive.content)
        }

    @Test
    fun `a send returns the server's id and time from the client, never through the session`() =
        runBlocking {
            val fake = FakeSession()
            val client = RecordingClient()
            val ack = driver(fake, client).sendMessage("hello")
            assertEquals(SentMessageAck("srv-1", "2026-10-08T14:00:01.123Z"), ack)
            assertEquals(listOf("hello"), client.sent.toList())
            assertEquals(listOf(ContentType.PLAIN_TEXT), client.sentTypes.toList())
            // An answer to a choice goes as Connect's interactive response.
            driver(fake, client).sendMessage("{}", interactiveResponse = true)
            assertEquals(ContentType.INTERACTIVE_RESPONSE, client.sentTypes.last())
            assertFalse("sendMessage" in fake.calls)
            assertFalse("resendFailedMessage" in fake.calls)
        }

    @Test
    fun `only a well-formed region outside the isolated and China partitions is used`() {
        for (ok in listOf("us-west-2", "ca-central-1", "ap-southeast-1", "us-gov-west-1")) assertTrue(ConnectChatSessionDriver.isParticipantRegion(ok), ok)
        for (no in listOf("us-iso-east-1", "us-isob-east-1", "eu-isoe-west-1", "cn-north-1", "", "US-WEST-2", "us-west-2.attacker.example", "attacker.example/x", "us-west-2 "))
            assertFalse(ConnectChatSessionDriver.isParticipantRegion(no), no)
    }

    @Test
    fun `a service error keeps its code and status, never its message`() =
        runBlocking {
            val client = RecordingClient()
            // As the service's client raises it: its code, here its type, and no status without a response.
            client.sendFailure = aws.sdk.kotlin.services.connectparticipant.model.AccessDeniedException { message = "token=SECRET" }
            val e = assertFailsWith<TouchpointException.TransportFailure> { driver(client = client).sendMessage("x") }
            assertEquals("AccessDeniedException", e.detail)
            assertFalse("SECRET" in e.toString())
        }

    @Test
    fun `transcript pages come from the service's own items, participant ids included`() =
        runBlocking {
            val client = RecordingClient()
            client.transcript =
                Result.success(
                    GetTranscriptResponse {
                        nextToken = "older"
                        transcript =
                            listOf(
                                Item { id = "e1"; type = ChatItemType.fromValue("EVENT"); contentType = ConnectContentType.JOINED; participantRole = ParticipantRole.fromValue("AGENT"); participantId = "p-ag"; displayName = "Ann"; absoluteTime = "t" },
                                Item { id = "m1"; type = ChatItemType.fromValue("MESSAGE"); contentType = "text/plain"; participantRole = ParticipantRole.fromValue("CUSTOMER"); participantId = "p-me"; content = "mine"; absoluteTime = "t" },
                                Item { id = "a1"; type = ChatItemType.fromValue("ATTACHMENT"); absoluteTime = "t" },
                            )
                    },
                )
            val d = driver(client = client)
            d.connect("c", "p-me", "tok")
            val page = d.fetchTranscript(null, 50)
            assertEquals("older", page.nextToken)
            assertEquals(listOf("e1", "m1"), page.items.map { it.id }) // attachments are out of v1 scope
            assertEquals("p-ag", page.items[0].participantId)
            assertTrue(page.items[1].isOutgoing)
        }

    @Test
    fun `a failed disconnect still stops the SDK, and a release never disconnects the participant`() =
        runBlocking {
            val fake = FakeSession()
            fake.disconnectFailure = java.io.IOException("offline")
            val d = driver(fake)
            assertFailsWith<TouchpointException.TransportFailure> { d.disconnect() }
            assertTrue("reset" in fake.calls)
            fake.calls.clear()
            d.release()
            assertEquals(listOf("reset"), fake.calls.filter { it == "reset" || it == "disconnect" })
        }

    @Test
    fun `a file in the service's history is one item, its name the text, and an address is asked for afresh`() =
        runBlocking {
            val client = RecordingClient()
            client.transcript =
                Result.success(
                    GetTranscriptResponse {
                        transcript =
                            listOf(
                                Item {
                                    id = "a1"; type = ChatItemType.fromValue("ATTACHMENT"); participantRole = ParticipantRole.fromValue("AGENT"); participantId = "p-ag"; displayName = "Ann"; absoluteTime = "t"
                                    attachments = listOf(aws.sdk.kotlin.services.connectparticipant.model.AttachmentItem { attachmentId = "att-7"; attachmentName = "receipt.pdf"; contentType = "application/pdf"})
                                },
                            )
                    },
                )
            val d = driver(client = client)
            d.connect("c", "p-me", "tok")
            val item = d.fetchTranscript(null, 50).items.single()
            assertEquals("att-7", item.attachmentId)
            assertEquals("receipt.pdf", item.text)
            assertEquals("application/pdf", item.contentType)
            assertFalse(item.isOutgoing)
            assertEquals("https://download.invalid/att-7", d.attachmentUrl("att-7"))
        }

    @Test
    fun `an upload is started with what the file is, refused over plain HTTP, and never through the SDK's own copy`() =
        runBlocking {
            val fake = FakeSession()
            val client = RecordingClient().apply { uploadAddress = "http://upload.invalid/x" }
            val d = driver(fake, client)
            d.connect("c", "p-me", "tok")
            val file = com.amazon.connect.touchpoint.core.AttachmentUpload("photo.png", "image/png", 3) { java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
            assertFailsWith<TouchpointException.TransportFailure> { d.uploadAttachment(file) }
            assertEquals(listOf("start:photo.png:image/png:3:true"), client.uploads.toList()) // not completed
            assertFalse(fake.calls.any { it.startsWith("sendAttachment") })
        }
}
