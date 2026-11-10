package com.amazon.connect.touchpoint.voice

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.amazon.connect.touchpoint.core.ChatContactCredentials
import com.amazon.connect.touchpoint.core.ChatContactRequest
import com.amazon.connect.touchpoint.core.ConversationStore
import com.amazon.connect.touchpoint.core.CredentialBroker
import com.amazon.connect.touchpoint.core.DispatchChangeReason
import com.amazon.connect.touchpoint.core.DispatchState
import com.amazon.connect.touchpoint.core.InMemorySecureStore
import com.amazon.connect.touchpoint.core.LiveSyncControl
import com.amazon.connect.touchpoint.core.LiveSyncCredentials
import com.amazon.connect.touchpoint.core.Modality
import com.amazon.connect.touchpoint.core.TouchpointBackend
import com.amazon.connect.touchpoint.core.TouchpointException
import com.amazon.connect.touchpoint.core.TouchpointSession
import com.amazon.connect.touchpoint.core.VoiceContactCredentials
import com.amazon.connect.touchpoint.core.VoiceContactRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The human-present signal on a voice contact, through the real session: a person joining the call
 * pauses Live Sync as it does on chat, and nothing but a person holds it paused.
 */
class VoiceDispatchGateTest {
    private class Backend : TouchpointBackend {
        override suspend fun startChatContact(request: ChatContactRequest): ChatContactCredentials = throw TouchpointException.ContactNotFound()

        override suspend fun startVoiceContact(request: VoiceContactRequest) = VoiceContactCredentials("c-voice", "p-me", "tok", "{}", "{}", null)

        override suspend fun liveSyncCredentials(): LiveSyncCredentials = throw TouchpointException.ContactNotFound()
    }

    /** What the session tells Live Sync. */
    private class Control : LiveSyncControl {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override suspend fun suspendDispatch(reason: DispatchChangeReason) {
            calls += "suspend:$reason"
        }

        override suspend fun resumeDispatch() {
            calls += "resume"
        }

        override suspend fun teardown() {
            calls += "teardown"
        }
    }

    private suspend fun eventually(cond: suspend () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            delay(5)
        }
        return cond()
    }

    private fun withVoiceSession(block: suspend (TouchpointSession, ConnectVoiceTransportTest.FakeDriver, Control) -> Unit) =
        runBlocking {
            val dir = kotlin.io.path.createTempDirectory("tp-voice-gate-").toFile()
            val keys = InMemorySecureStore()
            val store = ConversationStore.open(dir, keys, BundledSQLiteDriver())
            val driver = ConnectVoiceTransportTest.FakeDriver()
            val transport = ConnectVoiceTransport(driver, { true }, Dispatchers.Unconfined)
            val session = TouchpointSession(CredentialBroker(Backend(), keys), store, mapOf(Modality.VOICE to transport))
            val control = Control()
            session.register(control)
            try {
                session.startVoice(VoiceContactRequest("a"))
                assertTrue(eventually { driver.events != null }, "the call never started")
                driver.fire(VoiceDriverEvent.Started(false))
                block(session, driver, control)
            } finally {
                session.close()
                store.close()
                dir.deleteRecursively()
            }
        }

    private fun attendee(id: String, ext: String) = VoiceAttendee(id, ext, local = false)

    @Test
    fun `an agent joining a voice call suspends Live Sync dispatch, and leaving resumes it`() =
        withVoiceSession { session, driver, control ->
            assertEquals(DispatchState.ENABLED, session.dispatchState)
            driver.fire(VoiceDriverEvent.Attendees(joined = true, listOf(VoiceAttendee("me", "customer", local = true))))
            driver.fire(VoiceDriverEvent.Attendees(joined = true, listOf(attendee("agent-1", "x-agent"))))
            assertTrue(eventually { session.dispatchState == DispatchState.SUSPENDED })
            assertEquals(DispatchChangeReason.HUMAN_AGENT_JOINED, session.dispatchChangeReason)
            assertTrue(eventually { "suspend:HUMAN_AGENT_JOINED" in control.calls })
            driver.fire(VoiceDriverEvent.Attendees(joined = false, listOf(attendee("agent-1", "x-agent"))))
            assertTrue(eventually { session.dispatchState == DispatchState.ENABLED })
            assertTrue(eventually { control.calls.last() == "resume" })
        }

    @Test
    fun `an attendee we cannot place pauses the assistant - a wrong guess fails safe`() =
        withVoiceSession { session, driver, _ ->
            driver.fire(VoiceDriverEvent.Attendees(joined = true, listOf(attendee("att-7", "supervisor-barge"))))
            assertTrue(eventually { session.dispatchState == DispatchState.SUSPENDED })
            assertTrue(session.participants().humanAgentPresent)
        }

    @Test
    fun `T10b - our own content share does not hold the gate after the agent leaves`() =
        withVoiceSession { session, driver, _ ->
            driver.fire(VoiceDriverEvent.Attendees(joined = true, listOf(attendee("agent-1", "x-agent"))))
            driver.fire(VoiceDriverEvent.Attendees(joined = true, listOf(VoiceAttendee("me#content", "customer", local = false))))
            assertTrue(eventually { session.participants().humanAgentPresent })
            driver.fire(VoiceDriverEvent.Attendees(joined = false, listOf(attendee("agent-1", "x-agent"))))
            assertTrue(eventually { !session.participants().humanAgentPresent })
            assertTrue(eventually { session.dispatchState == DispatchState.ENABLED })
        }

    @Test
    fun `a transfer - agent A leaves, agent B joins - ends suspended, with B on the call`() =
        withVoiceSession { session, driver, _ ->
            driver.fire(VoiceDriverEvent.Attendees(joined = true, listOf(attendee("agent-a", "a-agent"))))
            assertTrue(eventually { session.dispatchState == DispatchState.SUSPENDED })
            driver.fire(VoiceDriverEvent.Attendees(joined = false, listOf(attendee("agent-a", "a-agent"))))
            driver.fire(VoiceDriverEvent.Attendees(joined = true, listOf(attendee("agent-b", "b-agent"))))
            assertTrue(eventually { session.dispatchState == DispatchState.SUSPENDED && "agent-b" in session.participants() })
        }
}
