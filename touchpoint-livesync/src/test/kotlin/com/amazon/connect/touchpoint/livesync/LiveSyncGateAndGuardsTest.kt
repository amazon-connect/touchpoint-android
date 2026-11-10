package com.amazon.connect.touchpoint.livesync

import com.amazon.connect.touchpoint.core.DispatchChangeReason
import com.amazon.connect.touchpoint.core.DispatchState
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Steps 14b–14c: the dispatch gate, the lifecycle, the two guards, and every frame accounted for. */
class LiveSyncGateAndGuardsTest {
    private val invocation = """{"classification":"custom","action":"open_account","payload":{"accountType":"credit_card"}}"""

    private suspend fun connected(client: LiveSyncClient, transports: FakeTransportFactory, n: Int = 1): FakeTransport {
        client.connect()
        val socket = transports.waitFor(n) ?: error("no connection $n")
        socket.opened()
        assertTrue(eventually { client.connectionState.value == LiveSyncConnectionState.Connected })
        return socket
    }

    @Test
    fun `G - while a human agent is on the contact nothing moves the app, and it says why`() =
        runBlocking {
            val transports = FakeTransportFactory()
            val client = makeTestClient(transports = transports)
            client.advertise("open_account")
            val actions = Collections.synchronizedList(mutableListOf<String>())
            val changes = Collections.synchronizedList(mutableListOf<Pair<DispatchState, DispatchChangeReason?>>())
            client.onAction = { actions += it.action }
            client.onDispatchStateChange = { s, r -> changes += s to r }
            val socket = connected(client, transports)
            client.suspendDispatch(DispatchChangeReason.HUMAN_AGENT_JOINED)
            socket.frame(invocation)
            assertTrue(eventually { client.diagnostics.value.suppressedInvocations == 1 })
            assertTrue(actions.isEmpty())
            client.resumeDispatch()
            socket.frame(invocation)
            assertTrue(eventually { actions.size == 1 })
            assertEquals(listOf(DispatchState.SUSPENDED to DispatchChangeReason.HUMAN_AGENT_JOINED, DispatchState.ENABLED to null), changes.toList())
            client.close()
        }

    @Test
    fun `L - background is resumable, teardown is final and forgets the handlers`() =
        runBlocking {
            val transports = FakeTransportFactory()
            val client = makeTestClient(transports = transports)
            connected(client, transports)
            client.enterBackground()
            assertTrue(eventually { client.connectionState.value == LiveSyncConnectionState.Disconnected })
            client.enterForeground()
            assertTrue(transports.waitFor(2) != null) // reconnected
            client.onAction = { }
            client.onDispatchStateChange = { _, _ -> }
            client.onError = { }
            client.teardown()
            assertNull(client.onAction)
            assertNull(client.onDispatchStateChange)
            assertNull(client.onError)
            client.connect()
            client.enterForeground()
            Thread.sleep(50)
            assertEquals(2, transports.count) // nothing after teardown
            client.close()
        }

    @Test
    fun `a zero or negative guard threshold disables the guard - a zero idle guard would reconnect without pause`() =
        runBlocking {
            val transports = FakeTransportFactory()
            val idle = GateSleeper()
            val client = makeTestClient(transports = transports, idleReconnect = kotlin.time.Duration.ZERO, staleContact = (-1).seconds, idleSleeper = idle)
            connected(client, transports)
            client.sendContext(LiveSyncContext(listOf(LiveSyncAction("open_account", "Open an account"))))
            Thread.sleep(50)
            assertEquals(0, idle.pending) // neither guard armed
            assertEquals(1, transports.count)
            client.close()
        }

    @Test
    fun `Z - a socket quiet past the lease is reopened at once, without backoff`() =
        runBlocking {
            val transports = FakeTransportFactory()
            val idle = GateSleeper()
            val backoff = ManualSleeper()
            val client = makeTestClient(transports = transports, idleReconnect = 240.seconds, idleSleeper = idle, sleeper = backoff)
            val first = connected(client, transports)
            assertTrue(eventually { idle.pending > 0 })
            idle.fire()
            assertTrue(eventually { first.closed })
            assertTrue(transports.waitFor(2) != null)
            assertEquals(1, client.diagnostics.value.idleReconnects)
            assertTrue(backoff.slept.isEmpty()) // no backoff for a deliberate reconnect
            client.close()
        }

    @Test
    fun `Q - context posted and nothing back reports a stale contact once, and a frame clears it`() =
        runBlocking {
            val transports = FakeTransportFactory()
            val idle = GateSleeper()
            val errors = Collections.synchronizedList(mutableListOf<LiveSyncException>())
            val client = makeTestClient(transports = transports, staleContact = 45.seconds, idleSleeper = idle)
            client.onError = { errors += it }
            val socket = connected(client, transports)
            client.sendContext(LiveSyncContext(listOf(LiveSyncAction("open_account", "Open an account"))))
            assertTrue(eventually { idle.pending > 0 })
            idle.fire()
            assertTrue(eventually { errors.any { it is LiveSyncException.ContactMayBeStale } })
            assertTrue(client.contactMayBeStale)
            socket.frame("""{"classification":"custom","note":"Hello."}""")
            assertTrue(eventually { !client.contactMayBeStale })
            assertEquals(1, client.diagnostics.value.staleContactWarnings)
            client.close()
        }

    @Test
    fun `N - navigation reaches its handler, in both shapes - without one, or while suspended, it is counted`() =
        runBlocking {
            val transports = FakeTransportFactory()
            val client = makeTestClient(transports = transports)
            val seen = Collections.synchronizedList(mutableListOf<LiveSyncNavigation>())
            val socket = connected(client, transports)
            socket.frame("""{"classification":"navigation","action":"page_next"}""")
            assertTrue(eventually { client.diagnostics.value.unhandledNavigationFrames == 1 })
            client.onNavigation = { seen += it }
            socket.frame("""{"classification":"navigation","payload":{"action":"page_custom","destination":"disputes"}}""")
            assertTrue(eventually { seen.size == 1 })
            assertEquals(LiveSyncNavigation(LiveSyncNavigation.Kind.PageCustom, "disputes"), seen.single())
            client.suspendDispatch(DispatchChangeReason.HUMAN_AGENT_JOINED)
            socket.frame("""{"classification":"navigation","action":"page_previous"}""")
            assertTrue(eventually { client.diagnostics.value.unhandledNavigationFrames == 2 })
            socket.frame("""{"classification":"input","payload":{}}""")
            socket.frame("""{"classification":"custom","note":"One moment."}""")
            socket.frame("""{"classification":"something_new"}""")
            assertTrue(eventually { client.diagnostics.value.noteFrames == 1 && client.diagnostics.value.droppedInputFrames == 1 })
            assertEquals(LiveSyncNavigation.Kind.Other("page_sideways"), LiveSyncNavigation.Kind.of("page_sideways"))
            client.close()
        }

    @Test
    fun `a denied consent is reported at once, and a refused handshake drops the key`() =
        runBlocking {
            val transports = FakeTransportFactory()
            val credentials = FakeCredentialSource()
            val errors = Collections.synchronizedList(mutableListOf<LiveSyncException>())
            val client = makeTestClient(transports = transports, credentials = credentials)
            client.onError = { errors += it }
            val socket = connected(client, transports)
            socket.end(LiveSyncException.HttpFailure(403, null))
            assertTrue(eventually { credentials.invalidations == 1 })
            client.setConsent(ConsentDecision.DENIED)
            assertTrue(eventually { errors.any { it is LiveSyncException.ConsentNotGranted } })
            assertFalse(errors.any { it is LiveSyncException.ConsentNotGranted && errors.count { e -> e is LiveSyncException.ConsentNotGranted } > 1 })
            client.close()
        }

    private class AgentTransport : com.amazon.connect.touchpoint.core.ContactTransport {
        val channel = kotlinx.coroutines.channels.Channel<com.amazon.connect.touchpoint.core.TransportEvent>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        override val events: kotlinx.coroutines.flow.Flow<com.amazon.connect.touchpoint.core.TransportEvent> = channel.receiveAsFlow()

        override suspend fun connect(credentials: com.amazon.connect.touchpoint.core.ExpiringCredentials) {
            channel.trySend(com.amazon.connect.touchpoint.core.TransportEvent.Connected)
        }

        override suspend fun send(message: com.amazon.connect.touchpoint.core.OutboundMessage) = Unit

        override suspend fun disconnect() = Unit

        fun agent(joined: Boolean) =
            channel.trySend(
                if (joined) com.amazon.connect.touchpoint.core.TransportEvent.ParticipantJoined(com.amazon.connect.touchpoint.core.Participant("a1", com.amazon.connect.touchpoint.core.ParticipantRole.AGENT, "Sam"))
                else com.amazon.connect.touchpoint.core.TransportEvent.ParticipantLeft("a1"),
            )
    }

    private class Backend : com.amazon.connect.touchpoint.core.TouchpointBackend {
        override suspend fun startChatContact(request: com.amazon.connect.touchpoint.core.ChatContactRequest) = com.amazon.connect.touchpoint.core.ChatContactCredentials("c-ls", "p-me", "tok", null)

        override suspend fun startVoiceContact(request: com.amazon.connect.touchpoint.core.VoiceContactRequest): com.amazon.connect.touchpoint.core.VoiceContactCredentials = throw com.amazon.connect.touchpoint.core.TouchpointException.ContactNotFound()

        override suspend fun liveSyncCredentials(): com.amazon.connect.touchpoint.core.LiveSyncCredentials = throw com.amazon.connect.touchpoint.core.TouchpointException.ContactNotFound()
    }

    @Test
    fun `G5-G6 - through a real session an agent joining suspends and leaving resumes, and a client attached while one is on starts suspended`() =
        runBlocking {
            val dir = kotlin.io.path.createTempDirectory("tp-ls-gate-").toFile()
            val keys = com.amazon.connect.touchpoint.core.InMemorySecureStore()
            val store = com.amazon.connect.touchpoint.core.ConversationStore.open(dir, keys, androidx.sqlite.driver.bundled.BundledSQLiteDriver())
            val transport = AgentTransport()
            val session = com.amazon.connect.touchpoint.core.TouchpointSession(com.amazon.connect.touchpoint.core.CredentialBroker(Backend(), keys), store, mapOf(com.amazon.connect.touchpoint.core.Modality.CHAT to transport))
            try {
                session.startChat(com.amazon.connect.touchpoint.core.ChatContactRequest("a"))
                val first = makeTestClient()
                first.attach(session)
                transport.agent(joined = true)
                assertTrue(eventually { first.dispatchState.value == DispatchState.SUSPENDED })
                // Made while the agent is on: suspended the moment it attaches, not a little later.
                val late = makeTestClient()
                late.attach(session)
                assertEquals(DispatchState.SUSPENDED, late.dispatchState.value)
                transport.agent(joined = false)
                assertTrue(eventually { late.dispatchState.value == DispatchState.ENABLED }) // the session's control is the latest attached
                first.close()
                late.close()
            } finally {
                session.close()
                store.close()
                dir.deleteRecursively()
            }
        }

    @Test
    fun `Q2 - the stale-contact warning is once until a frame, not once per reconnect`() =
        runBlocking {
            val transports = FakeTransportFactory()
            val idle = GateSleeper()
            val errors = Collections.synchronizedList(mutableListOf<LiveSyncException>())
            val client = makeTestClient(transports = transports, staleContact = 45.seconds, idleSleeper = idle)
            client.onError = { errors += it }
            val first = connected(client, transports)
            client.sendContext(LiveSyncContext(listOf(LiveSyncAction("open_account", "Open an account"))))
            assertTrue(eventually { idle.pending > 0 })
            idle.fire()
            assertTrue(eventually { client.contactMayBeStale })
            first.end()
            val second = transports.waitFor(2) ?: error("no reconnect")
            second.opened()
            assertTrue(eventually { client.connectionState.value == LiveSyncConnectionState.Connected })
            client.sendContext(LiveSyncContext(listOf(LiveSyncAction("open_account", "Open an account"))))
            idle.fire()
            Thread.sleep(50)
            assertEquals(1, errors.count { it is LiveSyncException.ContactMayBeStale })
            client.close()
        }

    @Test
    fun `Q3 - a post that consent stopped during the vend arms no stale watch`() =
        runBlocking {
            val transports = FakeTransportFactory()
            val idle = GateSleeper()
            val credentials = FakeCredentialSource()
            val poster = FakePoster()
            val client = makeTestClient(credentials = credentials, transports = transports, poster = poster, staleContact = 45.seconds, idleSleeper = idle)
            connected(client, transports)
            val vend = kotlinx.coroutines.CompletableDeferred<Unit>()
            credentials.hold = vend
            val before = credentials.vends
            val post = async { client.sendContext(LiveSyncContext(listOf(LiveSyncAction("open_account", "Open an account")))) }
            assertTrue(eventually { credentials.vends > before })
            client.setConsent(ConsentDecision.HELD)
            vend.complete(Unit)
            post.await()
            Thread.sleep(50)
            assertTrue(poster.requests.isEmpty())
            assertEquals(0, idle.pending)
            client.close()
        }

    @Test
    fun `teardown completes the invocations flow, and a refused context post drops the key`() =
        runBlocking {
            val transports = FakeTransportFactory()
            val credentials = FakeCredentialSource()
            val poster = FakePoster(status = 403)
            val client = makeTestClient(transports = transports, credentials = credentials, poster = poster)
            connected(client, transports)
            runCatching { client.sendContext(LiveSyncContext(listOf(LiveSyncAction("open_account", "Open an account")))) }
            assertTrue(eventually { credentials.invalidations >= 1 })
            val collected = async { client.invocations.toList() }
            kotlinx.coroutines.delay(20)
            client.teardown()
            kotlinx.coroutines.withTimeout(2_000) { collected.await() } // completes, rather than waiting for ever
            // And a collector that starts after it completes at once.
            kotlinx.coroutines.withTimeout(2_000) { client.invocations.toList() }
            client.close()
        }

    @Test
    fun `confirmation - an action the customer must confirm is held, runs only once allowed, and a refusal is declined`() =
        runBlocking {
            val transports = FakeTransportFactory()
            val client = makeTestClient(transports = transports)
            client.advertise("open_account")
            val actions = Collections.synchronizedList(mutableListOf<String>())
            val errors = Collections.synchronizedList(mutableListOf<LiveSyncException>())
            client.onAction = { actions += it.action }
            client.onError = { errors += it }
            client.confirmationRequirements = mapOf("open_account" to ActionConfirmation("Open an account", "Open a credit card account?"))
            val socket = connected(client, transports)
            socket.frame(invocation)
            assertTrue(eventually { client.pendingConfirmation.value != null })
            assertEquals("Open a credit card account?", client.pendingConfirmation.value?.message)
            assertEquals("Open an account", client.pendingConfirmation.value?.actionDescription)
            assertTrue(actions.isEmpty(), "held: the handler waits for the customer")
            client.confirmPendingAction()
            assertTrue(eventually { actions == listOf("open_account") })
            // Refused: dropped, counted, and told to onError.
            socket.frame(invocation)
            assertTrue(eventually { client.pendingConfirmation.value != null })
            client.cancelPendingAction()
            assertTrue(eventually { client.diagnostics.value.declinedConfirmations == 1 })
            assertEquals(1, actions.size)
            assertTrue(errors.any { it is LiveSyncException.ActionHandlerFailed && it.detail == "declined by user" })
        }

    @Test
    fun `confirmation - one ask at a time - a second is declined and the first stands`() =
        runBlocking {
            val transports = FakeTransportFactory()
            val client = makeTestClient(transports = transports)
            client.advertise("open_account")
            val actions = Collections.synchronizedList(mutableListOf<String>())
            client.onAction = { actions += it.action }
            client.confirmationRequirements = mapOf("open_account" to ActionConfirmation("Open an account"))
            val socket = connected(client, transports)
            socket.frame(invocation)
            assertTrue(eventually { client.pendingConfirmation.value != null })
            val first = client.pendingConfirmation.value?.id
            socket.frame(invocation)
            assertTrue(eventually { client.diagnostics.value.declinedConfirmations == 1 })
            assertEquals(first, client.pendingConfirmation.value?.id)
        }

    @Test
    fun `confirmation - what the customer has not answered goes when an agent joins, consent is withdrawn, the screen goes, or the contact ends`() =
        runBlocking {
            val transports = FakeTransportFactory()
            val client = makeTestClient(transports = transports)
            client.advertise("open_account")
            val actions = Collections.synchronizedList(mutableListOf<String>())
            client.onAction = { actions += it.action }
            val required = mapOf("open_account" to ActionConfirmation("Open an account"))
            client.confirmationRequirements = required
            client.setConsent(ConsentDecision.GRANTED)
            var socket = connected(client, transports)
            suspend fun held() {
                socket.frame(invocation)
                assertTrue(eventually { client.pendingConfirmation.value != null })
            }
            held()
            client.suspendDispatch(DispatchChangeReason.HUMAN_AGENT_JOINED)
            assertTrue(eventually { client.pendingConfirmation.value == null && client.diagnostics.value.declinedConfirmations == 1 })
            client.resumeDispatch()
            held()
            client.confirmationRequirements = emptyMap() // its screen has gone
            assertTrue(eventually { client.pendingConfirmation.value == null && client.diagnostics.value.declinedConfirmations == 2 })
            client.confirmationRequirements = required
            held()
            client.setConsent(ConsentDecision.DENIED)
            assertTrue(eventually { client.pendingConfirmation.value == null && client.diagnostics.value.declinedConfirmations == 3 })
            client.setConsent(ConsentDecision.GRANTED)
            client.advertise("open_account") // withdrawing consent cleared what was advertised
            socket = connected(client, transports, 2)
            held()
            client.teardown()
            assertTrue(eventually { client.pendingConfirmation.value == null && client.diagnostics.value.declinedConfirmations == 4 })
            assertTrue(actions.isEmpty())
        }
}
