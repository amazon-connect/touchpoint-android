package com.amazon.connect.touchpoint.voice

// The seam between ConnectVoiceTransport and the Chime SDK, as chat has one before its SDK: the
// transport's logic — states, ends, speaking — is tested on the JVM against a fake; the one real
// implementation, ChimeVoiceDriver, is the only code that touches Chime.

/** What the transport needs from a call. Every member is called on the main thread. */
internal interface VoiceDriving {
    /**
     * Join the meeting Connect's `StartWebRTCContact` returned, microphone and speaker on. Throws
     * [com.amazon.connect.touchpoint.core.TouchpointException] when it cannot even begin.
     */
    fun start(
        meetingJson: String,
        attendeeJson: String,
        events: (VoiceDriverEvent) -> Unit,
    )

    /** Leave the meeting: Connect ends a WebRTC contact when its customer leaves. */
    fun stop()

    fun setMuted(muted: Boolean)

    /** Web's Speakers: the other side's audio on or off. */
    fun setPlaybackMuted(muted: Boolean)

    /** Where the call is heard: one of the [VoiceDriverEvent.Outputs] last reported, by id. The customer's choice holds until that output goes. */
    fun chooseOutput(id: String)

    /** Listen for Connect's screen-sharing signal on the next [start]. */
    fun listenForScreenShare(listen: Boolean)

    /** Share [window] — this app's, nothing else — with the agent, as Chime content. */
    fun startScreenShare(window: android.view.Window)

    fun stopScreenShare()
}

/** What a call reports, on the main thread. */
internal sealed interface VoiceDriverEvent {
    /** Audio is flowing; [reconnecting] after a drop the SDK recovered from by itself. */
    data class Started(val reconnecting: Boolean) : VoiceDriverEvent

    /** Audio dropped; the SDK is reconnecting by itself. */
    data object Dropped : VoiceDriverEvent

    /** The SDK gave up reconnecting. */
    data object ReconnectAbandoned : VoiceDriverEvent

    /**
     * The call is over. [byUs] when we left it; otherwise the other side ended it, or the service did
     * — [status] the SDK's case name, never more.
     */
    data class Stopped(val byUs: Boolean, val status: String) : VoiceDriverEvent

    /** Who is speaking: the customer ([local]) or anyone else, as web shows it. */
    /** [immediate]: whoever stopped has left the call, so no half-second off-delay. */
    data class Speaking(val local: Boolean, val speaking: Boolean, val immediate: Boolean = false) : VoiceDriverEvent

    /** How loud the customer ([local]) or the other side is, 0 (silent) to 1: the guide's volume ring. */
    data class Volume(val local: Boolean, val level: Float) : VoiceDriverEvent

    /**
     * Attendees joined ([joined]) or left the meeting: everyone Chime reports, ours and content
     * shares included. The transport decides who is a participant.
     */
    data class Attendees(val joined: Boolean, val attendees: List<VoiceAttendee>) : VoiceDriverEvent

    /** Where the call can be heard, and where it is. */
    data class Outputs(val outputs: List<VoiceOutput>, val current: VoiceOutput?) : VoiceDriverEvent

    /** Another call — the phone's own — has the audio ([active]), or has given it back. */
    data class Interrupted(val active: Boolean) : VoiceDriverEvent

    /** Connect's word on screen sharing: the agent opened a session this customer may send to ([canSend]), or it is view-only or over. */
    data class ScreenShareSignal(val canSend: Boolean) : VoiceDriverEvent

    /** Chime's content share is up: the agent sees the screen. */
    data object ScreenShareStarted : VoiceDriverEvent

    /** It stopped: ours, the agent's, or a failure. */
    data object ScreenShareStopped : VoiceDriverEvent
}

/** A Chime attendee: [local] when it is this customer's own (not its content share). */
internal data class VoiceAttendee(val id: String, val externalUserId: String, val local: Boolean) {
    override fun toString(): String = "VoiceAttendee(local=$local, id=<redacted>)"
}
