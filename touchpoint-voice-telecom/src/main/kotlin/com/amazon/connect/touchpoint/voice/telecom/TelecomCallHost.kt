package com.amazon.connect.touchpoint.voice.telecom

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The system's side of a call: a self-managed outgoing audio call in Telecom, with no hold, and the
 * foreground service that keeps the app's audio while it is in the background. A Telecom that refuses
 * the call (a device without it, say) leaves the call foreground-only, as without this module: never
 * a crash.
 */
internal class TelecomCallHost(
    private val context: Context,
    private val smallIcon: Int,
) : BackgroundCallHost {
    private val calls = CallsManager(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var call: Job? = null
    private var active: CompletableDeferred<Unit>? = null
    private var over: CompletableDeferred<Unit>? = null

    init {
        try {
            calls.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
        } catch (_: Exception) {
            // no Telecom: each call stays the app's own
        }
    }

    override fun begin(
        displayName: String,
        hangUp: () -> Unit,
    ) {
        ActiveCall.hangUp = hangUp
        TouchpointCallService.start(context, displayName, smallIcon)
        val isActive = CompletableDeferred<Unit>().also { active = it }
        val isOver = CompletableDeferred<Unit>().also { over = it }
        val attributes =
            CallAttributesCompat(
                displayName,
                Uri.fromParts(ADDRESS_SCHEME, displayName, null),
                CallAttributesCompat.DIRECTION_OUTGOING,
                CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                // No hold: another call on the phone ends this one rather than leaving it half alive.
                0,
            )
        call =
            scope.launch {
                try {
                    calls.addCall(
                        attributes,
                        onAnswer = {},
                        // The system ended it — a car's or a headset's hang-up: the call goes.
                        onDisconnect = { hangUp() },
                        onSetActive = {},
                        onSetInactive = {},
                    ) {
                        launch {
                            isActive.await()
                            setActive()
                        }
                        launch {
                            isOver.await()
                            disconnect(DisconnectCause(DisconnectCause.LOCAL))
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // Telecom refused it: the foreground service still keeps the call going.
                }
            }
    }

    override fun connected() {
        active?.complete(Unit)
    }

    override fun end() {
        over?.complete(Unit)
        active = null
        over = null
        ActiveCall.hangUp = null
        context.stopService(Intent(context, TouchpointCallService::class.java))
    }

    override fun dispose() {
        scope.cancel()
    }

    private companion object {
        const val ADDRESS_SCHEME = "touchpoint"
    }
}

/** The call the notification's hang-up ends: one at a time, as the session has one contact at a time. */
internal object ActiveCall {
    @Volatile var hangUp: (() -> Unit)? = null
}
