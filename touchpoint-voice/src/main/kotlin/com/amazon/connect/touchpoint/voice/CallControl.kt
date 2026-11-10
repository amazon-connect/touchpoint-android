@file:OptIn(InternalTouchpointUi::class)

package com.amazon.connect.touchpoint.voice

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.amazon.connect.touchpoint.chat.ui.InternalTouchpointUi
import com.amazon.connect.touchpoint.core.ContactEvent
import com.amazon.connect.touchpoint.core.ContactState
import com.amazon.connect.touchpoint.core.EndReason
import com.amazon.connect.touchpoint.core.Modality
import com.amazon.connect.touchpoint.core.Permission
import com.amazon.connect.touchpoint.core.PermissionPolicy
import com.amazon.connect.touchpoint.core.TouchpointException
import com.amazon.connect.touchpoint.core.TouchpointSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a voice surface — web's full screen, or its mini — does with a call. */
internal class CallControl(
    val call: (hangUpFirst: Boolean) -> Unit,
    val hangUp: () -> Unit,
    /** It never started: web's failure, with Retry. */
    val failed: Boolean,
    /** The microphone is off and the surface may not ask: a state, not a failure. */
    val microphoneOff: Boolean,
    /** Hands the call to the host (Minimise): the surface going then leaves it running, and tells no end. */
    val handOver: () -> Unit = {},
)

/**
 * A surface's call, as web's voice surfaces make one: shown, it calls — asking for the microphone
 * first, if [permissionPolicy] lets it — through [onStartCall]; gone, it hangs up, but not for a
 * rotation. A call runs to its start once begun, as Send does in chat. [onContactEnded] is told once
 * when a call ends that this surface showed connected, not for a restart's.
 */
@Composable
internal fun rememberCallControl(
    session: TouchpointSession,
    voice: ConnectVoiceTransport,
    onStartCall: suspend () -> Unit,
    onContactEnded: ((EndReason) -> Unit)?,
    permissionPolicy: PermissionPolicy,
    /** A call the host started and holds: shown, nothing is started; gone, it is left running (an adopted call). */
    adopted: Boolean = false,
): CallControl {
    val context = LocalContext.current
    // The microphone is off and the view may not ask: a state, not a failure.
    var microphoneOff by rememberSaveable(session) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val start by rememberUpdatedState(onStartCall)
    val ended by rememberUpdatedState(onContactEnded)
    var failed by rememberSaveable(session) { mutableStateOf(false) }
    var connected by rememberSaveable(session) { mutableStateOf(false) }
    // A restart's end is not the host's news, as on web.
    var restarting by remember { mutableStateOf(false) }
    // Shown, it calls once — not again after a rotation, or on its ended or failed screen.
    var started by rememberSaveable(session) { mutableStateOf(adopted) }
    // A start in flight, and whether the customer hung up before it landed.
    var starting by remember { mutableStateOf(false) }
    var abandoned by remember { mutableStateOf(false) }
    // Minimised: the call is the host's now. A Restart from a view still on screen takes it back.
    var handedOver by rememberSaveable(session) { mutableStateOf(false) }

    var asking by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    // Saved, as the question outlives a rotation: its answer then reaches a new view with no one waiting.
    var awaitingMicrophone by rememberSaveable(session) { mutableStateOf(false) }
    var afterRotation by remember { mutableStateOf<(Boolean) -> Unit>({}) }
    val microphone =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val waiting = asking
            if (waiting != null) {
                waiting.complete(granted)
            } else if (awaitingMicrophone) {
                awaitingMicrophone = false
                afterRotation(granted)
            }
        }

    suspend fun microphoneGranted(): Boolean {
        if (voice.microphoneGranted()) return true
        if (!permissionPolicy.allows(Permission.MICROPHONE)) return false
        val answer = CompletableDeferred<Boolean>().also { asking = it }
        awaitingMicrophone = true
        return try {
            microphone.launch(Manifest.permission.RECORD_AUDIO)
            answer.await()
        } catch (_: android.content.ActivityNotFoundException) {
            false
        } finally {
            asking = null
            awaitingMicrophone = false
        }
    }

    // A call runs to its start once begun, as Send does in chat: closing the view must not tear a
    // half-made contact down.
    fun call(hangUpFirst: Boolean) {
        started = true
        handedOver = false
        if (starting) return
        starting = true
        abandoned = false
        scope.launch(start = CoroutineStart.ATOMIC) {
            failed = false
            connected = false
            microphoneOff = false
            if (!microphoneGranted()) {
                starting = false
                microphoneOff = true
                return@launch
            }
            try {
                withContext(NonCancellable) {
                    // Another contact — a chat still open — is ended first: one contact at a time.
                    if ((hangUpFirst || session.modality != Modality.VOICE) && session.isActive) {
                        restarting = true
                        session.end()
                    }
                    start()
                    // Hung up while it was starting: the call that has just landed goes too.
                    if (abandoned) session.end()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: TouchpointException) {
                if (!abandoned) failed = true
            } finally {
                restarting = false
                starting = false
            }
        }
    }

    // The answer to a question asked before a rotation: call now if it was yes, as the first view would have.
    afterRotation = { granted -> if (granted) call(hangUpFirst = false) else microphoneOff = true }
    // Back from Settings with the microphone on: call, as the customer went there to.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val watcher =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                // Away from the app, its screen is not the agent's to see.
                if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) voice.stopScreenShare()
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && microphoneOff && voice.microphoneGranted()) {
                    call(hangUpFirst = false)
                }
            }
        lifecycle.addObserver(watcher)
        onDispose { lifecycle.removeObserver(watcher) }
    }

    fun hangUp() {
        if (starting) abandoned = true
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                withContext(NonCancellable) { session.end() }
            } catch (_: TouchpointException) {
                // already over
            }
        }
    }

    LaunchedEffect(session) {
        // A chat still open is not a call: call, ending it first.
        if (!started && (!session.isActive || session.modality != Modality.VOICE)) call(hangUpFirst = false)
    }
    // Gone from the screen, the call goes too, as web's unmount hangs up — but not for a rotation.
    val activity = context.findActivity()
    val hangUpOnLeave by rememberUpdatedState({ hangUp() })
    DisposableEffect(session) {
        onDispose {
            val leaving = activity?.isChangingConfigurations != true
            if (!adopted && !handedOver && leaving && (session.modality == Modality.VOICE || starting)) hangUpOnLeave()
        }
    }
    // From the session's events, not its state: a call that connects and ends at once passes through
    // Connected too quickly for a sampled state to show it, and must still read as ended, not failed.
    LaunchedEffect(session) {
        session.events.collect { e ->
            if (e is ContactEvent.StateChanged && e.state == ContactState.Connected) connected = true
            // Ended before it ever connected: it did not start — web's failure, not its ended screen.
            if (e is ContactEvent.StateChanged && e.state is ContactState.Ended && !connected && !restarting && !abandoned) failed = true
            if (e is ContactEvent.ContactEnded && connected && !restarting && !handedOver) {
                try {
                    ended?.invoke(e.reason)
                } catch (x: Exception) {
                    if (x is CancellationException) throw x // a host's own throw is dropped, as the session drops one
                }
            }
        }
    }
    return CallControl(::call, ::hangUp, failed, microphoneOff, handOver = { handedOver = true })
}

/** The activity behind [this] context, through any wrappers. */
internal tailrec fun android.content.Context.findActivity(): android.app.Activity? =
    when (this) {
        is android.app.Activity -> this
        is android.content.ContextWrapper -> baseContext.findActivity()
        else -> null
    }
