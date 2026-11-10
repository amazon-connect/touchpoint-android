package com.amazon.connect.touchpoint.voice

import com.amazon.connect.touchpoint.core.ContactEvent
import com.amazon.connect.touchpoint.core.ContactState
import com.amazon.connect.touchpoint.core.Modality
import com.amazon.connect.touchpoint.core.PermissionPolicy
import com.amazon.connect.touchpoint.core.TouchpointException
import com.amazon.connect.touchpoint.core.TouchpointSession
import com.amazon.connect.touchpoint.core.VoiceContactRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A call the host holds: started and ended here, and outliving any
 * view of it. [TouchpointVoiceRail] shows it, [TouchpointVoiceView] takes it over full screen and
 * hands it back, and the chat goes on beside it; none of them hangs it up by going away.
 *
 * Hold one per session, in whatever outlives the screens (a coordinator, a `ViewModel`), and call
 * [start] when the customer asks to call — the microphone granted first: [start] fails with
 * [TouchpointException.PermissionDenied] without it. The call's own state (muted, speakers, who is
 * speaking, screen share) is [voice]'s [ConnectVoiceTransport.call].
 *
 * Most hosts use the constructor that takes a `displayName`: the call then builds the
 * contact request itself. This one is for a host that builds its own request.
 *
 * @param onStartCall starts the voice contact: the host's `session.startVoice(request)`.
 */
public class TouchpointVoiceCall(
    public val session: TouchpointSession,
    public val voice: ConnectVoiceTransport,
    private val onStartCall: suspend () -> Unit,
    private val scope: CoroutineScope = MainScope(),
) {
    /**
     * A call that builds its own contact request from a session, a transport and a display name.
     *
     * @param displayName the name the contact is started with ([VoiceContactRequest.displayName]).
     *   For the system's call notification, pass it to `TouchpointBackgroundCalls.attach` too.
     * @param permissionPolicy whether the SDK's call views may ask for the microphone (for this call's
     *   full-screen [TouchpointVoiceView], and its Retry). [start] itself shows no system prompt: without
     *   the microphone it fails, so ask first or let the view ask. Default: never.
     * @param allowsScreenShare the customer may share this app's screen when the agent asks. Turns
     *   screen sharing on in [voice] and asks the backend for it ([VoiceContactRequest.screenShare]),
     *   which must then allow it on `StartWebRTCContact`. Default `false`.
     * @param attributes contact attributes for the flow, such as the screen the customer called from.
     */
    public constructor(
        session: TouchpointSession,
        voice: ConnectVoiceTransport,
        displayName: String,
        permissionPolicy: PermissionPolicy = PermissionPolicy.NEVER,
        allowsScreenShare: Boolean = false,
        attributes: Map<String, String> = emptyMap(),
    ) : this(
        session,
        voice,
        onStartCall = {
            voice.allowsScreenShare = allowsScreenShare
            session.startVoice(VoiceContactRequest(displayName, attributes, screenShare = allowsScreenShare))
        },
    ) {
        this.permissionPolicy = permissionPolicy
    }

    /** Whether the SDK's views of this call may ask for the microphone. */
    public var permissionPolicy: PermissionPolicy = PermissionPolicy.NEVER
        private set

    /** Where the call is, as a host draws it. */
    public enum class Phase { IDLE, CONNECTING, CONNECTED, ENDED, FAILED }

    /**
     * Screen sharing with the agent: [ScreenShareState.AVAILABLE] once the
     * agent asks, then starting and sharing. The rail and the full-screen view offer it themselves; this
     * is for a host's own control.
     */
    public val screenShare: StateFlow<ScreenShareState> =
        voice.call.map { it.screenShare }.stateIn(scope, SharingStarted.Eagerly, voice.call.value.screenShare)

    /**
     * Shares this app's [window] with the agent, once [screenShare] is [ScreenShareState.AVAILABLE]; the
     * host asks the customer first. Android copies the window itself, so other apps and the system's UI
     * are never sent. A no-op otherwise.
     */
    public fun startScreenShare(window: android.view.Window): Unit = voice.startScreenShare(window)

    /** Stops sharing; a no-op unless starting or sharing. */
    public fun stopScreenShare(): Unit = voice.stopScreenShare()

    private val mutablePhase = MutableStateFlow(Phase.IDLE)

    /** Idle until [start]; [Phase.ENDED] once the contact ends, [Phase.FAILED] when it never started. */
    public val phase: StateFlow<Phase> = mutablePhase.asStateFlow()

    /** Connecting or connected: what a host's launcher gives way to. */
    public val isInCall: Boolean get() = phase.value == Phase.CONNECTING || phase.value == Phase.CONNECTED

    private var abandoned = false

    init {
        // The session says where the call is: connected, reconnecting, ended — whoever ended it.
        scope.launch {
            session.events.collect { e ->
                if (session.modality != Modality.VOICE) return@collect
                when {
                    e is ContactEvent.StateChanged && e.state == ContactState.Connected -> mutablePhase.value = Phase.CONNECTED
                    e is ContactEvent.ContactEnded && mutablePhase.value != Phase.IDLE && mutablePhase.value != Phase.FAILED -> mutablePhase.value = Phase.ENDED
                }
            }
        }
    }

    /**
     * Calls, ending any other contact on the session first (one contact at a time). Runs to its start
     * once begun, as Send does in chat: leaving the screen that asked does not tear a half-made contact
     * down. A second [start] while one is under way does nothing.
     */
    public fun start() {
        if (isInCall) return
        abandoned = false
        mutablePhase.value = Phase.CONNECTING
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                withContext(NonCancellable) {
                    if (session.isActive) session.end()
                    onStartCall()
                    // Ended while it was starting: the call that has just landed goes too.
                    if (abandoned) session.end()
                }
                if (session.state.value == ContactState.Connected) mutablePhase.value = Phase.CONNECTED
            } catch (e: TouchpointException) {
                if (!abandoned) mutablePhase.value = Phase.FAILED
            }
        }
    }

    /** Hangs up. Nothing while idle. */
    public fun end() {
        if (mutablePhase.value == Phase.CONNECTING) abandoned = true
        if (mutablePhase.value == Phase.IDLE || mutablePhase.value == Phase.FAILED) return
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                withContext(NonCancellable) { session.end() }
            } catch (_: TouchpointException) {
                // already over
            }
            mutablePhase.value = Phase.ENDED
        }
    }

    /** The host's start, for a view's Restart and Retry on this call. */
    internal suspend fun startContact() {
        mutablePhase.value = Phase.CONNECTING
        try {
            onStartCall()
        } catch (e: TouchpointException) {
            mutablePhase.value = Phase.FAILED
            throw e
        }
        if (session.state.value == ContactState.Connected) mutablePhase.value = Phase.CONNECTED
    }

    public fun setMuted(muted: Boolean): Unit = voice.setMuted(muted)

    public fun setSpeakersOn(on: Boolean): Unit = voice.setSpeakersOn(on)
}
