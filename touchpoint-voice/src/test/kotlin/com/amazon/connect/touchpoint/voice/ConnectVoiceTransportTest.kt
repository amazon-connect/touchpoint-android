package com.amazon.connect.touchpoint.voice

import com.amazon.connect.touchpoint.core.EndReason
import com.amazon.connect.touchpoint.core.Participant
import com.amazon.connect.touchpoint.core.ParticipantRole
import com.amazon.connect.touchpoint.core.Permission
import com.amazon.connect.touchpoint.core.TouchpointException
import com.amazon.connect.touchpoint.core.TransportEvent
import com.amazon.connect.touchpoint.core.VoiceContactCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The transport's decisions against a fake call: what the session hears, and what web's controls show. */
class ConnectVoiceTransportTest {
    internal class FakeDriver : VoiceDriving {
        var events: ((VoiceDriverEvent) -> Unit)? = null
        val calls = mutableListOf<String>()

        override fun start(meetingJson: String, attendeeJson: String, events: (VoiceDriverEvent) -> Unit) {
            calls += "start"
            this.events = events
        }

        override fun stop() {
            calls += "stop"
        }

        override fun setMuted(muted: Boolean) {
            calls += "muted=$muted"
        }

        override fun setPlaybackMuted(muted: Boolean) {
            calls += "playbackMuted=$muted"
        }

        override fun chooseOutput(id: String) {
            calls += "output=$id"
        }

        override fun listenForScreenShare(listen: Boolean) {
            calls += "listen=$listen"
        }

        override fun startScreenShare(window: android.view.Window) {
            calls += "share"
        }

        override fun stopScreenShare() {
            calls += "unshare"
        }

        fun fire(e: VoiceDriverEvent) = events?.invoke(e)
    }

    private val creds = VoiceContactCredentials("c1", "p1", "tok", "{}", "{}", null)

    private fun transport(driver: FakeDriver, mic: Boolean = true) = ConnectVoiceTransport(driver, { mic }, Dispatchers.Unconfined)

    @Test
    fun `a call that starts is Connected, and one the other side ends is the agent's end`() =
        runBlocking {
            val d = FakeDriver()
            val t = transport(d)
            t.connect(creds)
            d.fire(VoiceDriverEvent.Started(reconnecting = false))
            d.fire(VoiceDriverEvent.Stopped(byUs = false, status = "AudioCallEnded"))
            val events = withTimeout(2.seconds) { t.events.take(2).toList() }
            assertEquals(listOf(TransportEvent.Connected, TransportEvent.ContactEnded(EndReason.AGENT_ENDED)), events)
        }

    @Test
    fun `after a hang-up, the next call starts only once the last one has stopped - Chime's teardown is process-wide`() =
        runBlocking {
            val d = FakeDriver()
            val t = transport(d)
            t.connect(creds)
            d.fire(VoiceDriverEvent.Started(false))
            val first = d.events
            t.disconnect() // the hang-up: stop is asked for, and Chime is still tearing down
            val next = launch { t.connect(creds) }
            kotlinx.coroutines.delay(200)
            assertEquals(listOf("start"), d.calls.filter { it == "start" }, "a second call began before the first had stopped")
            first?.invoke(VoiceDriverEvent.Stopped(byUs = true, status = "Left"))
            withTimeout(2.seconds) { next.join() }
            assertEquals(2, d.calls.count { it == "start" })
        }

    @Test
    fun `our own leaving is not reported as an end, and a recovered drop is Reestablished`() =
        runBlocking {
            val d = FakeDriver()
            val t = transport(d)
            t.connect(creds)
            d.fire(VoiceDriverEvent.Started(false))
            d.fire(VoiceDriverEvent.Dropped)
            d.fire(VoiceDriverEvent.Started(true))
            t.disconnect()
            d.fire(VoiceDriverEvent.Stopped(byUs = false, status = "AudioCallEnded")) // racing our stop
            val events = withTimeout(2.seconds) { t.events.take(2).toList() }
            assertEquals(listOf(TransportEvent.Connected, TransportEvent.Reestablished), events)
            assertTrue("stop" in d.calls)
        }

    @Test
    fun `how loud each side is reaches the call, the customer silent while muted, everyone after a drop`() =
        runBlocking {
            val d = FakeDriver()
            val t = transport(d)
            t.connect(creds)
            d.fire(VoiceDriverEvent.Started(false))
            d.fire(VoiceDriverEvent.Volume(local = false, level = 1f))
            d.fire(VoiceDriverEvent.Volume(local = true, level = 0.5f))
            assertEquals(1f, t.call.value.agentVolume)
            assertEquals(0.5f, t.call.value.userVolume)
            t.setMuted(true)
            assertEquals(0f, t.call.value.userVolume)
            d.fire(VoiceDriverEvent.Volume(local = true, level = 1f))
            assertEquals(0f, t.call.value.userVolume)
            d.fire(VoiceDriverEvent.Dropped)
            assertEquals(0f, t.call.value.agentVolume)
            // Another call taking the audio mutes the customer, and silences their ring with it.
            t.setMuted(false)
            d.fire(VoiceDriverEvent.Started(true))
            d.fire(VoiceDriverEvent.Volume(local = true, level = 1f))
            assertEquals(1f, t.call.value.userVolume)
            d.fire(VoiceDriverEvent.Interrupted(active = true))
            assertEquals(0f, t.call.value.userVolume)
        }

    @Test
    fun `a drop says it is reconnecting and silences everyone, until Chime has it back`() =
        runBlocking {
            val d = FakeDriver()
            val t = transport(d)
            t.connect(creds)
            d.fire(VoiceDriverEvent.Started(false))
            d.fire(VoiceDriverEvent.Speaking(local = false, speaking = true))
            assertTrue(t.call.value.agentSpeaking)
            d.fire(VoiceDriverEvent.Dropped)
            assertTrue(t.call.value.reconnecting)
            assertFalse(t.call.value.agentSpeaking)
            d.fire(VoiceDriverEvent.Started(true))
            assertFalse(t.call.value.reconnecting)
        }

    @Test
    fun `another call mutes this one, and the customer's own mute comes back after - including one changed meanwhile`() =
        runBlocking {
            val d = FakeDriver()
            val t = transport(d)
            t.connect(creds)
            d.fire(VoiceDriverEvent.Started(false))
            d.fire(VoiceDriverEvent.Interrupted(true))
            assertTrue(t.call.value.interrupted)
            assertTrue(t.call.value.muted)
            assertEquals("muted=true", d.calls.last())
            d.fire(VoiceDriverEvent.Interrupted(false))
            assertFalse(t.call.value.muted) // they had not muted: back on
            assertEquals("muted=false", d.calls.last())
            t.setMuted(true)
            d.fire(VoiceDriverEvent.Interrupted(true))
            d.fire(VoiceDriverEvent.Interrupted(false))
            assertTrue(t.call.value.muted) // they had: still muted
            d.fire(VoiceDriverEvent.Interrupted(true))
            t.setMuted(false) // changed their mind meanwhile: kept, but nothing reaches the other call
            assertTrue(t.call.value.muted)
            assertEquals("muted=true", d.calls.last())
            d.fire(VoiceDriverEvent.Interrupted(false))
            assertFalse(t.call.value.muted)
            assertEquals("muted=false", d.calls.last())
        }

    @Test
    fun `screen sharing follows the agent's session - offered, started, ended by either side, and never shared unasked`() =
        runBlocking {
            val d = FakeDriver()
            val t = ConnectVoiceTransport(d, { true }, Dispatchers.Unconfined, allowsScreenShare = true)
            t.connect(creds)
            assertTrue("listen=true" in d.calls)
            d.fire(VoiceDriverEvent.Started(false))
            t.share { d.calls += "share" }
            assertFalse("share" in d.calls) // not before the agent asks
            d.fire(VoiceDriverEvent.ScreenShareSignal(canSend = true))
            assertEquals(ScreenShareState.AVAILABLE, t.call.value.screenShare)
            t.share { d.calls += "share" }
            assertEquals(ScreenShareState.STARTING, t.call.value.screenShare)
            d.fire(VoiceDriverEvent.ScreenShareStarted)
            assertEquals(ScreenShareState.SHARING, t.call.value.screenShare)
            // The agent ends the session: sharing stops at once.
            d.fire(VoiceDriverEvent.ScreenShareSignal(canSend = false))
            assertEquals(ScreenShareState.UNAVAILABLE, t.call.value.screenShare)
            assertEquals("unshare", d.calls.last())
            // A share landing that nobody asked for is ended.
            d.fire(VoiceDriverEvent.ScreenShareStarted)
            assertEquals("unshare", d.calls.last())
            assertEquals(ScreenShareState.UNAVAILABLE, t.call.value.screenShare)
            // The customer stops; the call's end clears it too.
            d.fire(VoiceDriverEvent.ScreenShareSignal(canSend = true))
            t.share { d.calls += "share" }
            t.stopScreenShare()
            assertEquals(ScreenShareState.AVAILABLE, t.call.value.screenShare)
            d.fire(VoiceDriverEvent.Stopped(byUs = false, status = "AudioCallEnded"))
            assertEquals(ScreenShareState.UNAVAILABLE, t.call.value.screenShare)
        }

    @Test
    fun `without the host's leave the signal is not listened for`() =
        runBlocking {
            val d = FakeDriver()
            transport(d).connect(creds)
            assertTrue("listen=false" in d.calls)
        }

    @Test
    fun `the outputs are reported and one is chosen by id`() =
        runBlocking {
            val d = FakeDriver()
            val t = transport(d)
            t.connect(creds)
            d.fire(VoiceDriverEvent.Started(false))
            val speaker = VoiceOutput("s", VoiceOutput.Kind.SPEAKER)
            val buds = VoiceOutput("b", VoiceOutput.Kind.BLUETOOTH, "Buds")
            d.fire(VoiceDriverEvent.Outputs(listOf(speaker, buds), buds))
            assertEquals(listOf(speaker, buds), t.call.value.outputs)
            assertEquals(buds, t.call.value.output)
            t.setOutput(speaker)
            assertEquals("output=s", d.calls.last())
        }

    @Test
    fun `a mute or speakers set while connecting is carried into the call`() =
        runBlocking {
            val d = FakeDriver()
            val t = transport(d)
            t.connect(creds)
            t.setMuted(true)
            t.setSpeakersOn(false)
            d.fire(VoiceDriverEvent.Started(false))
            assertTrue(d.calls.containsAll(listOf("muted=true", "playbackMuted=true")))
            assertTrue(t.call.value.muted)
            assertFalse(t.call.value.speakersOn)
        }

    @Test
    fun `speaking shows at once and goes quiet only after half a second, and never while muted`() =
        runBlocking {
            val d = FakeDriver()
            val t = transport(d)
            t.connect(creds)
            d.fire(VoiceDriverEvent.Speaking(local = false, speaking = true))
            assertTrue(t.call.value.agentSpeaking)
            d.fire(VoiceDriverEvent.Speaking(local = false, speaking = false))
            assertTrue(t.call.value.agentSpeaking) // not yet
            delay(ConnectVoiceTransport.QUIET_AFTER * 2)
            assertFalse(t.call.value.agentSpeaking)
            t.setMuted(true)
            d.fire(VoiceDriverEvent.Speaking(local = true, speaking = true))
            assertFalse(t.call.value.userSpeaking)
        }

    @Test
    fun `someone leaving stops their speaking at once, with no half-second off-delay`() =
        runBlocking {
            val d = FakeDriver()
            val t = transport(d)
            t.connect(creds)
            d.fire(VoiceDriverEvent.Speaking(local = false, speaking = true))
            assertTrue(t.call.value.agentSpeaking)
            d.fire(VoiceDriverEvent.Speaking(local = false, speaking = false, immediate = true))
            assertFalse(t.call.value.agentSpeaking)
        }

    @Test
    fun `without the microphone there is no call`() =
        runBlocking {
            val d = FakeDriver()
            val e = assertFailsWith<TouchpointException.PermissionDenied> { transport(d, mic = false).connect(creds) }
            assertEquals(Permission.MICROPHONE, e.permission)
            assertTrue(d.calls.isEmpty())
        }

    @Test
    fun `a call the SDK cannot recover is a lost connection`() =
        runBlocking {
            val d = FakeDriver()
            val t = transport(d)
            t.connect(creds)
            d.fire(VoiceDriverEvent.ReconnectAbandoned)
            assertEquals(TransportEvent.Disconnected("reconnect abandoned"), withTimeout(2.seconds) { t.events.first() })
        }

    @Test
    fun `a late stop from a call given up on never reaches the next call`() =
        runBlocking {
            val d = FakeDriver()
            val t = transport(d)
            t.connect(creds)
            val first = d.events
            first?.invoke(VoiceDriverEvent.Started(false))
            first?.invoke(VoiceDriverEvent.ReconnectAbandoned)
            first?.invoke(VoiceDriverEvent.Stopped(byUs = false, status = "AudioDisconnected")) // Chime's stop after giving up
            t.connect(creds) // the next call
            first?.invoke(VoiceDriverEvent.Started(false)) // and anything else the old call still says
            d.fire(VoiceDriverEvent.Started(false))
            assertEquals(TransportEvent.Connected, withTimeout(2.seconds) { t.events.first() })
            assertFalse(d.calls.isEmpty())
        }

    @Test
    fun `a call that never connects fails rather than loading for ever`() =
        runBlocking {
            val d = FakeDriver()
            val t = ConnectVoiceTransport(d, { true }, Dispatchers.Unconfined)
            t.connect(creds)
            val e = withTimeout(ConnectVoiceTransport.CONNECT_TIMEOUT + 5.seconds) { t.events.first() }
            assertEquals(TransportEvent.Disconnected("connect timeout"), e)
            assertTrue("stop" in d.calls)
        }

    private fun att(id: String, ext: String = "", local: Boolean = false) = VoiceAttendee(id, ext, local)

    @Test
    fun `V7 - remote attendees are participants - an agent by its externalUserId, anyone else unknown, so human - and our own is not`() =
        runBlocking {
            val d = FakeDriver()
            val t = transport(d)
            t.connect(creds)
            d.fire(VoiceDriverEvent.Started(false))
            d.fire(VoiceDriverEvent.Attendees(joined = true, listOf(att("att-local", local = true))))
            d.fire(VoiceDriverEvent.Attendees(joined = true, listOf(att("att-9", "abc-agent"), att("att-7", "something-else"))))
            d.fire(VoiceDriverEvent.Attendees(joined = false, listOf(att("att-9", "abc-agent"))))
            val events = withTimeout(2.seconds) { t.events.take(4).toList() }
            assertEquals(
                listOf(
                    TransportEvent.Connected,
                    TransportEvent.ParticipantJoined(Participant("att-9", ParticipantRole.AGENT, "")),
                    TransportEvent.ParticipantJoined(Participant("att-7", ParticipantRole.UNKNOWN, "")),
                    TransportEvent.ParticipantLeft("att-9"),
                ),
                events,
            )
            assertTrue(ParticipantRole.UNKNOWN.isHuman, "an attendee we cannot place pauses the assistant")
            assertEquals(ParticipantRole.AGENT, ConnectVoiceTransport.roleOf("X-AGENT"))
        }

    @Test
    fun `T10 - content shares, ours or the agent's, are neither participants nor leavers`() =
        runBlocking {
            val d = FakeDriver()
            val t = transport(d)
            t.connect(creds)
            d.fire(VoiceDriverEvent.Started(false))
            d.fire(VoiceDriverEvent.Attendees(joined = true, listOf(att("att-local#content"), att("agent-1", "x-agent"), att("agent-1#content", "x-agent"))))
            d.fire(VoiceDriverEvent.Attendees(joined = false, listOf(att("att-local#content"))))
            d.fire(VoiceDriverEvent.Attendees(joined = true, listOf(att("sentinel", "x-agent"))))
            val events = withTimeout(2.seconds) { t.events.take(3).toList() }
            assertEquals(
                listOf(
                    TransportEvent.Connected,
                    TransportEvent.ParticipantJoined(Participant("agent-1", ParticipantRole.AGENT, "")),
                    TransportEvent.ParticipantJoined(Participant("sentinel", ParticipantRole.AGENT, "")),
                ),
                events,
                "one agent joined, not a second for its share, and no one left",
            )
        }

    @Test
    fun `an attendee from a call given up on never reaches the next call`() =
        runBlocking {
            val d = FakeDriver()
            val t = transport(d)
            t.connect(creds)
            val first = d.events
            t.disconnect()
            first?.invoke(VoiceDriverEvent.Stopped(byUs = true, status = "Left"))
            t.connect(creds)
            first?.invoke(VoiceDriverEvent.Attendees(joined = true, listOf(att("old", "x-agent"))))
            d.fire(VoiceDriverEvent.Started(false))
            assertEquals(TransportEvent.Connected, withTimeout(2.seconds) { t.events.first() })
        }
}
