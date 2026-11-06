package com.amazon.connect.touchpoint.voice.telecom

import android.content.Context
import androidx.annotation.DrawableRes
import com.amazon.connect.touchpoint.core.ContactState
import com.amazon.connect.touchpoint.core.Modality
import com.amazon.connect.touchpoint.core.TouchpointSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * An in-app call that survives the background: while [session] has a voice call, the system knows
 * of it as a call — Telecom, through Jetpack core-telecom — and a foreground service keeps its audio
 * and microphone, with a call notification that returns to the app or hangs up. Another call on the
 * phone ends this one, as it cannot be held. Without this module a call goes quiet when the app
 * leaves the screen.
 *
 * Opt-in, and what it adds to the host's manifest is the point of opting in: core-telecom's
 * ConnectionService, receiver and permissions (`MANAGE_OWN_CALLS`, `MODIFY_AUDIO_SETTINGS`,
 * `BLUETOOTH_CONNECT`), and this module's foreground service with `FOREGROUND_SERVICE_PHONE_CALL` and
 * `FOREGROUND_SERVICE_MICROPHONE`. Play asks the host to declare its foreground-service use.
 *
 * ```
 * val background = TouchpointBackgroundCalls.attach(context, session, displayName = "Acme Bank", smallIcon = R.drawable.ic_call)
 * // … for as long as the session; then
 * background.close()
 * ```
 */
public class TouchpointBackgroundCalls internal constructor(
    session: TouchpointSession,
    host: BackgroundCallHost,
    displayName: String,
    scope: CoroutineScope,
) : AutoCloseable {
    private val coordinator = BackgroundCallCoordinator(session.state, { session.modality }, { session.end() }, host, displayName, scope)

    init {
        coordinator.start()
    }

    /** Stops following the session; a call under way is no longer the system's. */
    override fun close(): Unit = coordinator.close()

    public companion object {
        /**
         * Follows [session]'s calls from now on.
         *
         * @param displayName who the customer is talking to, as the system shows it: "Acme Bank".
         * @param smallIcon the call notification's icon, the host's own.
         */
        public fun attach(
            context: Context,
            session: TouchpointSession,
            displayName: String,
            @DrawableRes smallIcon: Int,
        ): TouchpointBackgroundCalls {
            val app = context.applicationContext
            return TouchpointBackgroundCalls(session, TelecomCallHost(app, smallIcon), displayName, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        }
    }
}

/** What the system is told of a call: the seam between the session's call and Telecom, tested against a fake. */
internal interface BackgroundCallHost {
    /** A call began. [hangUp] when the system, or the notification, ends it. */
    fun begin(
        displayName: String,
        hangUp: () -> Unit,
    )

    /** It has audio: the system shows it active. */
    fun connected()

    /** It is over, or no longer followed. */
    fun end()

    /** Followed no longer: what the host holds goes. */
    fun dispose() {}
}

/** Follows the session: a voice contact begun is a system call; connected, active; ended, gone. */
internal class BackgroundCallCoordinator(
    private val states: StateFlow<ContactState>,
    private val modality: () -> Modality?,
    private val end: suspend () -> Unit,
    private val host: BackgroundCallHost,
    private val displayName: String,
    private val scope: CoroutineScope,
) {
    private var watch: Job? = null
    private var inCall = false
    private var shownActive = false

    fun start() {
        watch =
            scope.launch {
                states.collect { state ->
                    val voice = modality() == Modality.VOICE
                    val live = state == ContactState.Connecting || state == ContactState.Connected || state is ContactState.Reconnecting
                    when {
                        voice && live && !inCall -> {
                            inCall = true
                            shownActive = false
                            host.begin(displayName) {
                                scope.launch {
                                    try {
                                        end()
                                    } catch (e: Exception) {
                                        if (e is kotlinx.coroutines.CancellationException) throw e // ours stays ours
                                    }
                                }
                            }
                            if (state == ContactState.Connected) connected()
                        }
                        inCall && state == ContactState.Connected -> connected()
                        inCall && !(voice && live) -> {
                            inCall = false
                            host.end()
                        }
                    }
                }
            }
    }

    private fun connected() {
        if (shownActive) return
        shownActive = true
        host.connected()
    }

    fun close() {
        watch?.cancel()
        if (inCall) host.end()
        inCall = false
        host.dispose()
        scope.cancel()
    }
}
