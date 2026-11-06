package com.amazon.connect.touchpoint.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.amazon.connect.touchpoint.core.ContactTransport
import com.amazon.connect.touchpoint.core.EndReason
import com.amazon.connect.touchpoint.core.ExpiringCredentials
import com.amazon.connect.touchpoint.core.OutboundMessage
import com.amazon.connect.touchpoint.core.Participant
import com.amazon.connect.touchpoint.core.ParticipantRole
import com.amazon.connect.touchpoint.core.Permission
import com.amazon.connect.touchpoint.core.TouchpointException
import com.amazon.connect.touchpoint.core.TransportEvent
import com.amazon.connect.touchpoint.core.VoiceContactCredentials
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * In-app voice over Amazon Connect, as Touchpoint web calls: the meeting `StartWebRTCContact` returned
 * (through the host's backend, `TouchpointBackend.startVoiceContact`), joined with the Chime SDK, audio
 * only. Register it with the session — `TouchpointSession(…, transports = mapOf(Modality.VOICE to
 * transport))` — and start a call with `session.startVoice(request)`.
 *
 * The call's own controls are here, beside the session's: [call] (muted, speakers, who is speaking,
 * where it is heard), [setMuted], [setSpeakersOn], [setOutput]. No reconnect of our own: Chime
 * recovers a dropped call by itself within 30 s, and [VoiceCallState.reconnecting] says so
 * meanwhile; one it cannot recover ends, and a new call is a new contact. Another call — the phone's
 * own — mutes this one ([VoiceCallState.interrupted]), and the customer's own mute comes back after.
 *
 * The host declares `RECORD_AUDIO` and `MODIFY_AUDIO_SETTINGS` and asks for the microphone before a
 * call (`TouchpointVoiceView` does); without it [connect] fails with
 * [TouchpointException.PermissionDenied].
 */
public class ConnectVoiceTransport internal constructor(
    private val driver: VoiceDriving,
    /** Whether the microphone is on: the view's check too, so the two never disagree. */
    internal val microphoneGranted: () -> Boolean,
    private val main: kotlin.coroutines.CoroutineContext = Dispatchers.Main.immediate,
    /** Listen for the agent's screen-sharing session; the host's request asks for it too ([com.amazon.connect.touchpoint.core.VoiceContactRequest.screenShare]). */
    allowsScreenShare: Boolean = false,
) : ContactTransport {
    /** Read at each connect; a [TouchpointVoiceCall] made with `allowsScreenShare` sets it before it calls. */
    @Volatile
    internal var allowsScreenShare: Boolean = allowsScreenShare

    private val channel = Channel<TransportEvent>(Channel.UNLIMITED)
    override val events: Flow<TransportEvent> = channel.receiveAsFlow()

    private val mutableCall = MutableStateFlow(VoiceCallState())

    /** The call as web shows it: muted, speakers, and who is speaking. */
    public val call: StateFlow<VoiceCallState> = mutableCall.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + main)

    /** The current call: an event from any other — a late stop, a start queued before a hang-up — is dropped. */
    private var callId = 0L
    private var live = false

    /** Completes when the last call's stop has been reported: Chime will not start again before. */
    private var stopped: CompletableDeferred<Unit>? = null
    private var connectTimeout: Job? = null
    private val quiet = arrayOfNulls<Job>(2)

    override suspend fun connect(credentials: ExpiringCredentials) {
        val voice = credentials as? VoiceContactCredentials ?: throw TouchpointException.InvalidConfiguration("voice needs the credentials startVoiceContact returns")
        if (!microphoneGranted()) throw TouchpointException.PermissionDenied(Permission.MICROPHONE)
        withContext(main) { leave() }
        // Chime stops in the background and ignores a start until it has: wait for the last call to go.
        stopped?.let { withTimeoutOrNull(STOP_WAIT) { it.await() } }
        withContext(main) {
            // Nothing from an earlier call may reach the session for this one.
            while (channel.tryReceive().isSuccess) Unit
            val id = ++callId
            live = true
            val done = CompletableDeferred<Unit>().also { stopped = it }
            mutableCall.value = VoiceCallState()
            mutedBeforeInterruption = false
            driver.listenForScreenShare(allowsScreenShare)
            // Its stop counts for the next call's wait even once the call itself no longer does.
            try {
                driver.start(voice.meetingJson, voice.attendeeJson) { e ->
                    if (e is VoiceDriverEvent.Stopped) done.complete(Unit)
                    if (id == callId) onEvent(e)
                }
            } catch (e: Throwable) {
                // It never began: nothing to stop, and nothing for the next call to wait for.
                live = false
                done.complete(Unit)
                throw e
            }
            // Web's call has no deadline; one that never connects here fails, rather than loading for ever.
            connectTimeout =
                scope.launch {
                    delay(CONNECT_TIMEOUT)
                    if (id == callId && live) {
                        leave()
                        channel.trySend(TransportEvent.Disconnected("connect timeout"))
                    }
                }
        }
    }

    override suspend fun send(message: OutboundMessage): Unit = throw TouchpointException.InvalidConfiguration("a voice call carries no messages")

    override suspend fun disconnect() {
        withContext(main) { leave() }
    }

    /** Muted: the microphone sends nothing. Web's Voice / Microphone control. */
    public fun setMuted(muted: Boolean) {
        scope.launch {
            // On hold, the microphone stays off, so nothing reaches the other call: the customer's
            // choice is kept, and is what the call comes back with.
            if (mutableCall.value.interrupted) {
                mutedBeforeInterruption = muted
                return@launch
            }
            if (live) driver.setMuted(muted)
            mutableCall.update { it.copy(muted = muted, userSpeaking = it.userSpeaking && !muted, userVolume = if (muted) 0f else it.userVolume) }
        }
    }

    /**
     * Shares [window] — this app's screen, nothing else — with the agent, once
     * [VoiceCallState.screenShare] is [ScreenShareState.AVAILABLE]: the agent opened a session. The
     * host asks the customer first (`TouchpointVoiceView` does). Frames are copied from the window,
     * so other apps, notifications and the system's own UI are never sent, and a window the app marks
     * secure (`FLAG_SECURE`) sends nothing at all.
     */
    public fun startScreenShare(window: android.view.Window): Unit = share { driver.startScreenShare(window) }

    /** The share's decision, apart from the window it shares: only when the agent has asked. */
    internal fun share(begin: () -> Unit) {
        scope.launch {
            if (!live || mutableCall.value.screenShare != ScreenShareState.AVAILABLE) return@launch
            mutableCall.update { it.copy(screenShare = ScreenShareState.STARTING) }
            begin()
        }
    }

    /** Stops sharing; a no-op unless starting or sharing. */
    public fun stopScreenShare() {
        scope.launch {
            val now = mutableCall.value.screenShare
            if (now != ScreenShareState.STARTING && now != ScreenShareState.SHARING) return@launch
            mutableCall.update { it.copy(screenShare = ScreenShareState.AVAILABLE) }
            driver.stopScreenShare()
        }
    }

    /** Where the call is heard: one of [VoiceCallState.outputs]. A mobile need web has no control for. */
    public fun setOutput(output: VoiceOutput) {
        scope.launch { if (live) driver.chooseOutput(output.id) }
    }

    private var mutedBeforeInterruption = false

    /** The other side's audio on or off. Web's Speakers control. */
    public fun setSpeakersOn(on: Boolean) {
        scope.launch {
            if (live) driver.setPlaybackMuted(!on)
            mutableCall.update { it.copy(speakersOn = on) }
        }
    }

    /** Hang up the current call, if there is one; its events stop counting at once. */
    private fun leave() {
        connectTimeout?.cancel()
        // Only the call's own Stopped — or a start that failed — ends the next call's wait. Marking it done
        // here, on a second leave after the hang-up's, let the next call start while Chime was still tearing
        // the last one down, its process-wide video state freed under the new one: "Oops", or a crash.
        if (live) driver.stop()
        live = false
        callId++
        quiet.forEach { it?.cancel() }
        mutableCall.update { it.copy(userSpeaking = false, agentSpeaking = false, userVolume = 0f, agentVolume = 0f, reconnecting = false, interrupted = false, screenShare = ScreenShareState.UNAVAILABLE) }
    }

    private fun onEvent(event: VoiceDriverEvent) {
        when (event) {
            is VoiceDriverEvent.Started -> {
                connectTimeout?.cancel()
                // Carry the controls into the call: a mute or speakers set while it connected.
                val state = mutableCall.value
                if (state.muted) driver.setMuted(true)
                if (!state.speakersOn) driver.setPlaybackMuted(true)
                mutableCall.update { it.copy(reconnecting = false) }
                channel.trySend(if (event.reconnecting) TransportEvent.Reestablished else TransportEvent.Connected)
            }
            // Chime is reconnecting by itself: the call says so, and no one is heard speaking meanwhile.
            VoiceDriverEvent.Dropped -> {
                quiet.forEach { it?.cancel() }
                mutableCall.update { it.copy(reconnecting = true, userSpeaking = false, agentSpeaking = false, userVolume = 0f, agentVolume = 0f) }
            }
            is VoiceDriverEvent.Outputs -> mutableCall.update { it.copy(outputs = event.outputs, output = event.current) }
            is VoiceDriverEvent.Interrupted -> interrupted(event.active)
            is VoiceDriverEvent.ScreenShareSignal ->
                if (event.canSend) {
                    mutableCall.update { if (it.screenShare == ScreenShareState.UNAVAILABLE) it.copy(screenShare = ScreenShareState.AVAILABLE) else it }
                } else {
                    // View-only, or over: the customer must not be sharing.
                    val was = mutableCall.value.screenShare
                    mutableCall.update { it.copy(screenShare = ScreenShareState.UNAVAILABLE) }
                    if (was == ScreenShareState.STARTING || was == ScreenShareState.SHARING) driver.stopScreenShare()
                }
            // A share nobody asked for — one landing after the customer stopped — is ended, not left showing the screen.
            VoiceDriverEvent.ScreenShareStarted ->
                if (mutableCall.value.screenShare == ScreenShareState.STARTING) {
                    mutableCall.update { it.copy(screenShare = ScreenShareState.SHARING) }
                } else {
                    driver.stopScreenShare()
                }
            VoiceDriverEvent.ScreenShareStopped ->
                mutableCall.update {
                    if (it.screenShare == ScreenShareState.STARTING || it.screenShare == ScreenShareState.SHARING) it.copy(screenShare = ScreenShareState.AVAILABLE) else it
                }
            VoiceDriverEvent.ReconnectAbandoned -> {
                // The stop that follows is this one, not the other side's.
                channel.trySend(TransportEvent.Disconnected("reconnect abandoned"))
                leave()
            }
            is VoiceDriverEvent.Stopped -> {
                live = false
                quiet.forEach { it?.cancel() }
                mutableCall.update { it.copy(userSpeaking = false, agentSpeaking = false, userVolume = 0f, agentVolume = 0f, reconnecting = false, interrupted = false, screenShare = ScreenShareState.UNAVAILABLE) }
                // Web: any end but our own leaving is the other side's. Ours the session already knows.
                if (!event.byUs) channel.trySend(TransportEvent.ContactEnded(EndReason.AGENT_ENDED))
            }
            is VoiceDriverEvent.Attendees -> attendees(event)
            is VoiceDriverEvent.Speaking -> speaking(event.local, event.speaking, event.immediate)
            // Muted, the customer makes no sound, whatever the microphone hears.
            is VoiceDriverEvent.Volume ->
                mutableCall.update { if (event.local) it.copy(userVolume = if (it.muted) 0f else event.level) else it.copy(agentVolume = event.level) }
        }
    }

    /**
     * Everyone else on the call is a participant: the session's Live Sync gate pauses the
     * assistant while a person is on it. Our own attendee and every content share (`…#content`, ours
     * or the agent's) are not: ours would hold the gate after the agent left, the agent's would be a
     * second agent. A join is not dropped for arriving before the audio has started: a
     * person reported early still pauses the assistant.
     */
    private fun attendees(event: VoiceDriverEvent.Attendees) {
        for (a in event.attendees) {
            if (a.local || isContentAttendee(a.id)) continue
            channel.trySend(if (event.joined) TransportEvent.ParticipantJoined(Participant(a.id, roleOf(a.externalUserId), "")) else TransportEvent.ParticipantLeft(a.id))
        }
    }

    /**
     * Another call has the audio: muted, so a half-resumed call never leaks into it, and the
     * customer's own mute restored when it gives the audio back.
     */
    private fun interrupted(active: Boolean) {
        val state = mutableCall.value
        if (active == state.interrupted) return
        if (active) {
            mutedBeforeInterruption = state.muted
            driver.setMuted(true)
            mutableCall.update { it.copy(interrupted = true, muted = true, userSpeaking = false, userVolume = 0f) }
        } else {
            if (!mutedBeforeInterruption) driver.setMuted(false)
            mutableCall.update { it.copy(interrupted = false, muted = mutedBeforeInterruption) }
        }
    }

    /** As web: speaking at once, quiet only after [QUIET_AFTER] of silence; never while muted. */
    private fun speaking(
        local: Boolean,
        speaking: Boolean,
        immediate: Boolean = false,
    ) {
        val slot = if (local) 0 else 1
        quiet[slot]?.cancel()
        if (speaking && !(local && mutableCall.value.muted)) {
            set(local, true)
        } else if (immediate) {
            // Gone is not a pause between words.
            set(local, false)
        } else {
            quiet[slot] =
                scope.launch {
                    delay(QUIET_AFTER)
                    set(local, false)
                }
        }
    }

    private fun set(
        local: Boolean,
        on: Boolean,
    ) = mutableCall.update { if (local) it.copy(userSpeaking = on) else it.copy(agentSpeaking = on) }

    public companion object {
        internal val QUIET_AFTER: Duration = 500.milliseconds
        internal val CONNECT_TIMEOUT: Duration = 30.seconds
        private val STOP_WAIT: Duration = 5.seconds

        /** Chime's content-share attendee: `<attendeeId>#content`, ours or the agent's. */
        internal fun isContentAttendee(id: String): Boolean = id.endsWith("#content")

        /**
         * Connect's agent attendee has an `externalUserId` ending in `agent`. Anyone else is
         * [ParticipantRole.UNKNOWN], which counts as human, so a wrong guess pauses the assistant
         * rather than letting it act during a call a person is handling.
         */
        internal fun roleOf(externalUserId: String): ParticipantRole = if (externalUserId.lowercase().endsWith("agent")) ParticipantRole.AGENT else ParticipantRole.UNKNOWN

        /**
         * The production transport, over the Chime SDK for Android.
         *
         * @param allowsScreenShare the customer may share this app's screen when the agent asks; set
         *   [com.amazon.connect.touchpoint.core.VoiceContactRequest.screenShare] too, so the backend asks Connect for it.
         */
        public fun live(
            context: Context,
            allowsScreenShare: Boolean = false,
        ): ConnectVoiceTransport {
            val app = context.applicationContext
            return ConnectVoiceTransport(
                ChimeVoiceDriver(app),
                { app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
                allowsScreenShare = allowsScreenShare,
            )
        }

        /**
         * A call with no audio and no network, for demos, previews and screen recordings: it connects
         * after a moment, then the assistant and the customer take turns speaking on [script]'s timer,
         * so the views' loader, ripples, mute and Speakers show as they do on a real call. It needs no
         * microphone. Any [VoiceContactCredentials] start it, placeholders included; never ship it as
         * the app's voice.
         */
        public fun scripted(script: VoiceScript = VoiceScript()): ConnectVoiceTransport = ConnectVoiceTransport(ScriptedVoiceDriver(script), { true })
    }
}

/**
 * A call as Touchpoint web shows it. [userSpeaking] and [agentSpeaking] drive the guide's inner ring:
 * on as soon as someone speaks, off half a second after they stop. [userVolume] and [agentVolume]
 * size its outer ring.
 */
public data class VoiceCallState(
    val muted: Boolean = false,
    val speakersOn: Boolean = true,
    val userSpeaking: Boolean = false,
    val agentSpeaking: Boolean = false,
    /** Chime is recovering a dropped call, for up to 30 s. */
    val reconnecting: Boolean = false,
    /** Another call has the audio; this one is muted until it gives it back. */
    val interrupted: Boolean = false,
    /** Where the call can be heard: the speaker, the earpiece, a headset. */
    val outputs: List<VoiceOutput> = emptyList(),
    /** Where it is heard now; `null` before the call has audio. */
    val output: VoiceOutput? = null,
    /** Whether the customer can share this app's screen with the agent, or is. */
    val screenShare: ScreenShareState = ScreenShareState.UNAVAILABLE,
    /** How loud the customer is, 0 to 1 (0 while muted): Chime's three steps of loudness. */
    val userVolume: Float = 0f,
    /** How loud the other side is, 0 to 1. */
    val agentVolume: Float = 0f,
)

/** Sharing this app's screen with the agent: the agent opens a session, and only then can the customer start. */
public enum class ScreenShareState {
    /** No session from the agent, the host did not allow it, or the call is not live. */
    UNAVAILABLE,

    /** The agent opened a session: the customer may share. */
    AVAILABLE,

    /** The customer chose to share; Chime's content attendee is joining. */
    STARTING,

    /** The agent sees the screen. */
    SHARING,
}

/** Where a call can be heard. [name] is the device's own for a headset ("Pixel Buds"), and `null` for the phone's own speaker and earpiece. */
public data class VoiceOutput(
    val id: String,
    val kind: Kind,
    val name: String? = null,
) {
    public enum class Kind { SPEAKER, EARPIECE, WIRED_HEADSET, USB_HEADSET, BLUETOOTH }
}
