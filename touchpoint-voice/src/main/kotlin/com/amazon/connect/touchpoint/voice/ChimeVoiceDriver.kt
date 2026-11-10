@file:OptIn(com.amazon.connect.touchpoint.core.InternalTouchpointApi::class)

package com.amazon.connect.touchpoint.voice

import com.amazon.connect.touchpoint.core.TouchpointSecurityEvents
import com.amazon.connect.touchpoint.core.TouchpointSecurityEvent
import android.content.Context
import com.amazon.connect.touchpoint.core.TouchpointException
import android.media.AudioManager
import com.amazonaws.services.chime.sdk.meetings.audiovideo.AttendeeInfo
import com.amazonaws.services.chime.sdk.meetings.audiovideo.contentshare.ContentShareObserver
import com.amazonaws.services.chime.sdk.meetings.audiovideo.contentshare.ContentShareSource
import com.amazonaws.services.chime.sdk.meetings.audiovideo.contentshare.ContentShareStatus
import com.amazonaws.services.chime.sdk.meetings.realtime.datamessage.DataMessage
import com.amazonaws.services.chime.sdk.meetings.realtime.datamessage.DataMessageObserver
import com.amazonaws.services.chime.sdk.meetings.audiovideo.AudioVideoConfiguration
import com.amazonaws.services.chime.sdk.meetings.audiovideo.AudioVideoFacade
import com.amazonaws.services.chime.sdk.meetings.audiovideo.AudioVideoObserver
import com.amazonaws.services.chime.sdk.meetings.audiovideo.SignalUpdate
import com.amazonaws.services.chime.sdk.meetings.audiovideo.VolumeLevel
import com.amazonaws.services.chime.sdk.meetings.audiovideo.VolumeUpdate
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.RemoteVideoSource
import com.amazonaws.services.chime.sdk.meetings.device.DeviceChangeObserver
import com.amazonaws.services.chime.sdk.meetings.device.MediaDevice
import com.amazonaws.services.chime.sdk.meetings.device.MediaDeviceType
import com.amazonaws.services.chime.sdk.meetings.realtime.RealtimeObserver
import com.amazonaws.services.chime.sdk.meetings.session.Attendee
import com.amazonaws.services.chime.sdk.meetings.session.CreateAttendeeResponse
import com.amazonaws.services.chime.sdk.meetings.session.CreateMeetingResponse
import com.amazonaws.services.chime.sdk.meetings.session.DefaultMeetingSession
import com.amazonaws.services.chime.sdk.meetings.session.MediaPlacement
import com.amazonaws.services.chime.sdk.meetings.session.Meeting
import com.amazonaws.services.chime.sdk.meetings.session.MeetingSessionConfiguration
import com.amazonaws.services.chime.sdk.meetings.session.MeetingSessionStatus
import com.amazonaws.services.chime.sdk.meetings.session.MeetingSessionStatusCode
import com.amazonaws.services.chime.sdk.meetings.utils.logger.LogLevel
import com.amazonaws.services.chime.sdk.meetings.utils.logger.Logger
import org.json.JSONException
import org.json.JSONObject

/**
 * The one class that touches the Chime SDK. Audio only; the SDK logs nothing — its messages carry the
 * meeting's URLs and join token. Audio goes to a headset when there is one, else the speaker:
 * an assistant on the call is heard as web's is, not held to the ear — unless the customer chooses
 * otherwise, which holds until that output goes or a headset arrives. A dropped call is recovered for
 * [RECONNECT_TIMEOUT_MS], rather than Chime's three minutes. The phone's own calls are seen
 * by the audio mode they set, which needs no permission.
 */
internal class ChimeVoiceDriver(
    private val context: Context,
) : VoiceDriving {
    private var audio: AudioVideoFacade? = null
    private var observers: Observers? = null
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var modeWatch: AudioManager.OnModeChangedListener? = null
    private var listensForShare = false
    private var capture: AppWindowCapture? = null

    override fun start(
        meetingJson: String,
        attendeeJson: String,
        reported: (VoiceDriverEvent) -> Unit,
    ) {
        unwatchMode()
        // However the call ends — ours, theirs, the service's — the phone's calls stop being watched.
        val events: (VoiceDriverEvent) -> Unit = { e ->
            if (e is VoiceDriverEvent.Stopped) unwatchMode()
            reported(e)
        }
        val configuration =
            try {
                configuration(JSONObject(meetingJson), JSONObject(attendeeJson))
            } catch (_: JSONException) {
                throw TouchpointException.MalformedPayload("StartWebRTCContact returned no Chime connection data")
            }
        val session = DefaultMeetingSession(configuration, Silent, context)
        val av = session.audioVideo
        val o = Observers(av, configuration.credentials.attendeeId, events)
        av.addAudioVideoObserver(o)
        av.addRealtimeObserver(o)
        av.addDeviceChangeObserver(o)
        audio = av
        observers = o
        if (listensForShare) {
            // Connect's word on screen sharing, as its in-app calling sample listens for it.
            av.addRealtimeDataMessageObserver(SCREEN_SHARE_TOPIC, o)
            av.addContentShareObserver(o)
        }
        try {
            av.start(AudioVideoConfiguration(reconnectTimeoutMs = RECONNECT_TIMEOUT_MS))
        } catch (e: SecurityException) {
            // A call that never started leaves nothing registered behind it.
            av.removeAudioVideoObserver(o)
            av.removeRealtimeObserver(o)
            av.removeDeviceChangeObserver(o)
            audio = null
            observers = null
            throw TouchpointException.PermissionDenied(com.amazon.connect.touchpoint.core.Permission.MICROPHONE)
        }
        o.route(av.listAudioDevices())
        watchMode(events)
    }

    override fun stop() {
        unwatchMode()
        stopScreenShare()
        val av = audio ?: return
        observers?.leaving = true
        av.stop()
        audio = null
    }

    override fun chooseOutput(id: String) {
        observers?.choose(id)
    }

    override fun listenForScreenShare(listen: Boolean) {
        listensForShare = listen
    }

    override fun startScreenShare(window: android.view.Window) {
        val av = audio ?: return
        // One capture at a time: a second start replaces the first, never orphans its thread.
        capture?.stop()
        capture = null
        val source = AppWindowCapture(window).also { capture = it }
        source.start()
        av.startContentShare(ContentShareSource().apply { videoSource = source })
        TouchpointSecurityEvents.report(TouchpointSecurityEvent.ScreenShareStarted)
    }

    override fun stopScreenShare() {
        val source = capture ?: return
        capture = null
        source.stop()
        audio?.stopContentShare()
        TouchpointSecurityEvents.report(TouchpointSecurityEvent.ScreenShareStopped)
    }

    /** Ringing, or in a phone call: the phone's call has the audio. Back to ours, it has given it back. */
    private fun watchMode(events: (VoiceDriverEvent) -> Unit) {
        val manager = audioManager ?: return
        fun holding(mode: Int) = mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_CALL_SCREENING
        // The listener hears changes only: a call begun during the phone's own starts on hold.
        var interrupted = holding(manager.mode)
        if (interrupted) events(VoiceDriverEvent.Interrupted(true))
        val watch =
            AudioManager.OnModeChangedListener { mode ->
                val now = holding(mode)
                if (now != interrupted) {
                    interrupted = now
                    events(VoiceDriverEvent.Interrupted(now))
                }
            }
        manager.addOnModeChangedListener(context.mainExecutor, watch)
        modeWatch = watch
    }

    private fun unwatchMode() {
        modeWatch?.let { audioManager?.removeOnModeChangedListener(it) }
        modeWatch = null
    }

    override fun setMuted(muted: Boolean) {
        audio?.let { if (muted) it.realtimeLocalMute() else it.realtimeLocalUnmute() }
    }

    override fun setPlaybackMuted(muted: Boolean) {
        audio?.let { if (muted) it.realtimePlaybackMute() else it.realtimePlaybackUnmute() }
    }

    private class Observers(
        private val av: AudioVideoFacade,
        private val me: String,
        private val events: (VoiceDriverEvent) -> Unit,
    ) : AudioVideoObserver,
        RealtimeObserver,
        DeviceChangeObserver,
        DataMessageObserver,
        ContentShareObserver {
        @Volatile var leaving = false
        private var stopped = false

        // Chime tears its video client down after the audio, in its own coroutine, ending in the
        // process-wide `VideoClient.finalizeGlobals()`; a call started before that has its native state
        // freed under it (a start that fails, or a crash in `stopService`). So the call reports Stopped —
        // which the next call waits for — only once the video client has stopped too, and settled.
        private val main = android.os.Handler(android.os.Looper.getMainLooper())
        private var stoppedEvent: VoiceDriverEvent.Stopped? = null
        private var videoStopped = false
        private var reported = false
        private val speaking = HashMap<String, Boolean>()
        private val levels = HashMap<String, Float>()
        private var devices: List<MediaDevice> = emptyList()
        private var chosen: String? = null

        /** The customer's choice, if it is still there and no headset has just arrived; else a headset, else the speaker. */
        fun route(fresh: List<MediaDevice>) {
            val audio = fresh.filter { KIND[it.type] != null }
            val arrived = audio.any { it.type in HEADSETS && devices.none { d -> id(d) == id(it) } }
            devices = audio
            val choice = chosen?.takeIf { !arrived }?.let { c -> audio.firstOrNull { id(it) == c } }
            if (choice == null) chosen = null
            val target = choice ?: audio.minByOrNull { PREFERENCE.indexOf(it.type).let { i -> if (i < 0) PREFERENCE.size else i } }
            target?.let { if (it.type in PREFERENCE || choice != null) av.chooseAudioDevice(it) }
            report(target)
        }

        fun choose(id: String) {
            val device = devices.firstOrNull { id(it) == id } ?: return
            chosen = id
            av.chooseAudioDevice(device)
            report(device)
        }

        private fun report(current: MediaDevice?) = events(VoiceDriverEvent.Outputs(devices.map(::output), current?.let(::output)))

        override fun onAudioDeviceChanged(freshAudioDeviceList: List<MediaDevice>) = route(freshAudioDeviceList)

        override fun onAudioSessionStartedConnecting(reconnecting: Boolean) = Unit

        override fun onAudioSessionStarted(reconnecting: Boolean) = events(VoiceDriverEvent.Started(reconnecting))

        override fun onAudioSessionDropped() = events(VoiceDriverEvent.Dropped)

        override fun onAudioSessionCancelledReconnect() = events(VoiceDriverEvent.ReconnectAbandoned)

        override fun onAudioSessionStopped(sessionStatus: MeetingSessionStatus) {
            if (stopped) return
            stopped = true
            // A finished call's observers go with it: Chime keeps every one registered otherwise. This one
            // stays until the video client has stopped, to hear it.
            av.removeRealtimeObserver(this)
            av.removeDeviceChangeObserver(this)
            av.removeRealtimeDataMessageObserverFromTopic(SCREEN_SHARE_TOPIC)
            av.removeContentShareObserver(this)
            val code = sessionStatus.statusCode
            stoppedEvent = VoiceDriverEvent.Stopped(byUs = leaving || code == MeetingSessionStatusCode.Left, status = code?.name ?: "unknown")
            // A video client that never says it stopped is waited for no longer than this.
            main.postDelayed(::reportStopped, if (videoStopped) VIDEO_SETTLE_MS else VIDEO_STOP_WAIT_MS)
        }

        private fun reportStopped() {
            val event = stoppedEvent ?: return
            if (reported) return
            reported = true
            av.removeAudioVideoObserver(this)
            events(event)
        }

        override fun onVolumeChanged(volumeUpdates: Array<VolumeUpdate>) {
            for (u in volumeUpdates) {
                // A screen share's own attendee (`…#content`) is no one speaking.
                if (u.attendeeInfo.attendeeId.endsWith(CONTENT_SUFFIX)) continue
                val local = u.attendeeInfo.attendeeId == me
                val now = u.volumeLevel != VolumeLevel.NotSpeaking && u.volumeLevel != VolumeLevel.Muted
                val key = if (local) ME else u.attendeeInfo.attendeeId
                // Chime's three steps of loudness, for the guide's volume ring: the loudest of the other side.
                val level = levelOf(u.volumeLevel)
                if (levels[key] != level) {
                    levels[key] = level
                    events(VoiceDriverEvent.Volume(local, if (local) level else levels.filterKeys { it != ME }.values.maxOrNull() ?: 0f))
                }
                if (speaking[key] == now) continue
                speaking[key] = now
                // Anyone else counts as the other side, as web's application speaking.
                events(VoiceDriverEvent.Speaking(local, if (local) now else speaking.filterKeys { it != ME }.values.any { it }))
            }
        }

        private fun levelOf(level: VolumeLevel): Float =
            when (level) {
                VolumeLevel.Low -> VOLUME_LOW
                VolumeLevel.Medium -> VOLUME_MEDIUM
                VolumeLevel.High -> 1f
                else -> 0f
            }

        override fun onConnectionRecovered() = Unit

        override fun onConnectionBecamePoor() = Unit

        override fun onVideoSessionStartedConnecting() = Unit

        override fun onVideoSessionStarted(sessionStatus: MeetingSessionStatus) = Unit

        override fun onVideoSessionStopped(sessionStatus: MeetingSessionStatus) {
            videoStopped = true
            // Its destroy and finalizeGlobals follow at once, in the same coroutine: a moment for them.
            if (stoppedEvent != null) main.postDelayed(::reportStopped, VIDEO_SETTLE_MS)
        }

        override fun onRemoteVideoSourceAvailable(sources: List<RemoteVideoSource>) = Unit

        override fun onRemoteVideoSourceUnavailable(sources: List<RemoteVideoSource>) = Unit

        override fun onCameraSendAvailabilityUpdated(available: Boolean) = Unit

        override fun onSignalStrengthChanged(signalUpdates: Array<SignalUpdate>) = Unit

        // Who is on the call: the transport turns these into participants, so a person joining pauses
        // Live Sync on a voice contact as it does on chat.
        override fun onAttendeesJoined(attendeeInfo: Array<AttendeeInfo>) = events(VoiceDriverEvent.Attendees(joined = true, attendees(attendeeInfo)))

        override fun onAttendeesLeft(attendeeInfo: Array<AttendeeInfo>) {
            // A party who leaves mid-sentence must not leave the ripple running.
            onAttendeesDropped(attendeeInfo)
            events(VoiceDriverEvent.Attendees(joined = false, attendees(attendeeInfo)))
        }

        private fun attendees(info: Array<AttendeeInfo>) = info.map { VoiceAttendee(it.attendeeId, it.externalUserId, local = it.attendeeId == me) }

        override fun onDataMessageReceived(dataMessage: DataMessage) {
            if (dataMessage.topic != SCREEN_SHARE_TOPIC) return
            // `{"message": …}`; anything else is a newer message, ignored rather than misread.
            val message =
                try {
                    JSONObject(dataMessage.text()).optString("message")
                } catch (_: JSONException) {
                    return
                }
            when (message) {
                "ScreenSharingStarted" -> events(VoiceDriverEvent.ScreenShareSignal(canSend = true))
                "ScreenSharingStartedViewOnly", "ScreenSharingStopped" -> events(VoiceDriverEvent.ScreenShareSignal(canSend = false))
            }
        }

        override fun onContentShareStarted() = events(VoiceDriverEvent.ScreenShareStarted)

        override fun onContentShareStopped(status: ContentShareStatus) = events(VoiceDriverEvent.ScreenShareStopped)

        // Speaking stops for whoever drops: a voice that has gone is not still talking.
        override fun onAttendeesDropped(attendeeInfo: Array<AttendeeInfo>) {
            var changed = false
            var louder = false
            for (a in attendeeInfo) {
                val key = if (a.attendeeId == me) ME else a.attendeeId
                // A level the ring was showing goes with whoever had it.
                if ((levels.remove(key) ?: 0f) > 0f) louder = true
                if (speaking.remove(key) == true) changed = true
            }
            if (louder) {
                events(VoiceDriverEvent.Volume(local = false, level = levels.filterKeys { it != ME }.values.maxOrNull() ?: 0f))
                if (attendeeInfo.any { it.attendeeId == me }) events(VoiceDriverEvent.Volume(local = true, level = 0f))
            }
            if (changed) {
                events(VoiceDriverEvent.Speaking(local = false, speaking = speaking.filterKeys { it != ME }.values.any { it }, immediate = true))
                if (attendeeInfo.any { it.attendeeId == me }) events(VoiceDriverEvent.Speaking(local = true, speaking = false, immediate = true))
            }
        }

        override fun onAttendeesMuted(attendeeInfo: Array<AttendeeInfo>) = Unit

        override fun onAttendeesUnmuted(attendeeInfo: Array<AttendeeInfo>) = Unit
    }

    /** Silences the SDK's log: its lines carry URLs and the join token. */
    private object Silent : Logger {
        override fun verbose(tag: String, msg: String) = Unit

        override fun debug(tag: String, msg: String) = Unit

        override fun info(tag: String, msg: String) = Unit

        override fun warn(tag: String, msg: String) = Unit

        override fun error(tag: String, msg: String) = Unit

        override fun setLogLevel(level: LogLevel) = Unit

        override fun getLogLevel(): LogLevel = LogLevel.OFF
    }

    companion object {
        private const val ME = ""

        /** Chime's Low and Medium, as shares of its High. */
        private const val VOLUME_LOW = 1f / 3f
        private const val VOLUME_MEDIUM = 2f / 3f

        /** After the video client stops: its destroy and the process-wide finalize that follow it. */
        private const val VIDEO_SETTLE_MS = 400L

        /** The longest a stop waits for the video client to say it has stopped. */
        private const val VIDEO_STOP_WAIT_MS = 2_000L

        /** A headset first, as the person chose to wear it; then the speaker. The earpiece only when chosen. */
        private val PREFERENCE = listOf(MediaDeviceType.AUDIO_BLUETOOTH, MediaDeviceType.AUDIO_WIRED_HEADSET, MediaDeviceType.AUDIO_USB_HEADSET, MediaDeviceType.AUDIO_BUILTIN_SPEAKER)
        private val HEADSETS = setOf(MediaDeviceType.AUDIO_BLUETOOTH, MediaDeviceType.AUDIO_WIRED_HEADSET, MediaDeviceType.AUDIO_USB_HEADSET)
        private val KIND =
            mapOf(
                MediaDeviceType.AUDIO_BUILTIN_SPEAKER to VoiceOutput.Kind.SPEAKER,
                MediaDeviceType.AUDIO_HANDSET to VoiceOutput.Kind.EARPIECE,
                MediaDeviceType.AUDIO_WIRED_HEADSET to VoiceOutput.Kind.WIRED_HEADSET,
                MediaDeviceType.AUDIO_USB_HEADSET to VoiceOutput.Kind.USB_HEADSET,
                MediaDeviceType.AUDIO_BLUETOOTH to VoiceOutput.Kind.BLUETOOTH,
            )

        /** Connect's screen-sharing signal, a Chime realtime data message. */
        const val SCREEN_SHARE_TOPIC = "AMAZON_CONNECT_SCREEN_SHARING"

        /** Chime's content-share attendee: `<attendee>#content`, ours or the agent's. */
        private const val CONTENT_SUFFIX = "#content"

        /** 30 s, not Chime's 180, so a call that cannot come back says so in time to call again. */
        const val RECONNECT_TIMEOUT_MS = 30_000

        private fun id(device: MediaDevice): String = "${device.type.name}:${device.label}"

        /** A headset by its own name; the phone's speaker and earpiece by kind, named by the view. */
        private fun output(device: MediaDevice): VoiceOutput {
            val kind = KIND.getValue(device.type)
            return VoiceOutput(id(device), kind, device.label.takeIf { kind == VoiceOutput.Kind.BLUETOOTH && it.isNotBlank() })
        }

        /** Connect's ConnectionData, as Chime's types; its keys are Chime's own, capitalised. */
        fun configuration(
            meeting: JSONObject,
            attendee: JSONObject,
        ): MeetingSessionConfiguration {
            val placement = meeting.getJSONObject("MediaPlacement")
            val media =
                MediaPlacement(
                    placement.getString("AudioFallbackUrl"),
                    placement.getString("AudioHostUrl"),
                    placement.getString("SignalingUrl"),
                    placement.getString("TurnControlUrl"),
                    placement.optString("EventIngestionUrl", ""),
                )
            val m = Meeting(meeting.optString("ExternalMeetingId").takeIf { it.isNotEmpty() }, media, meeting.getString("MediaRegion"), meeting.getString("MeetingId"))
            val a = Attendee(attendee.getString("AttendeeId"), attendee.optString("ExternalUserId", attendee.getString("AttendeeId")), attendee.getString("JoinToken"))
            return MeetingSessionConfiguration(CreateMeetingResponse(m), CreateAttendeeResponse(a))
        }
    }
}
