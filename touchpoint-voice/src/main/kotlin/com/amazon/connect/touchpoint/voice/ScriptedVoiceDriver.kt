package com.amazon.connect.touchpoint.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A call with no audio and no network, for [ConnectVoiceTransport.scripted]: it connects after
 * [VoiceScript.connectAfter], then the assistant and the customer take turns speaking, on a timer,
 * for as long as the call lasts. Mute holds the customer's turns back (the transport shows no one
 * speaking while muted), and the assistant hangs up after [VoiceScript.endAfter] when that is set.
 * Everything runs on [scope], the main thread, as [VoiceDriving] asks.
 */
internal class ScriptedVoiceDriver(
    private val script: VoiceScript,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : VoiceDriving {
    private var call: Job? = null
    private var events: ((VoiceDriverEvent) -> Unit)? = null
    private var output: VoiceOutput = SPEAKER

    override fun start(
        meetingJson: String,
        attendeeJson: String,
        events: (VoiceDriverEvent) -> Unit,
    ) {
        call?.cancel()
        this.events = events
        output = SPEAKER
        call =
            scope.launch {
                delay(script.connectAfter)
                events(VoiceDriverEvent.Started(reconnecting = false))
                events(VoiceDriverEvent.Outputs(OUTPUTS, output))
                val turns = launch { turns(events) }
                script.endAfter?.let {
                    delay(it)
                    turns.cancel()
                    end(byUs = false)
                }
            }
    }

    private suspend fun turns(events: (VoiceDriverEvent) -> Unit) {
        while (true) {
            delay(script.pause)
            turn(local = false, script.assistantTurn, events)
            delay(script.pause)
            turn(local = true, script.customerTurn, events)
        }
    }

    /** One side speaking for [length], its loudness rising and falling as a voice's does, Chime's three steps. */
    private suspend fun turn(
        local: Boolean,
        length: Duration,
        events: (VoiceDriverEvent) -> Unit,
    ) {
        events(VoiceDriverEvent.Speaking(local, speaking = true))
        var spoken = Duration.ZERO
        var step = 0
        while (spoken < length) {
            events(VoiceDriverEvent.Volume(local, LEVELS[step++ % LEVELS.size]))
            delay(VOLUME_STEP)
            spoken += VOLUME_STEP
        }
        events(VoiceDriverEvent.Volume(local, 0f))
        events(VoiceDriverEvent.Speaking(local, speaking = false))
    }

    override fun stop() {
        val running = call ?: return
        running.cancel()
        call = null
        // Asynchronously, as Chime reports its stop: the transport never hears it inside its own leave.
        val e = events
        scope.launch { e?.invoke(VoiceDriverEvent.Stopped(byUs = true, status = "OK")) }
    }

    private fun end(byUs: Boolean) {
        call = null
        events?.invoke(VoiceDriverEvent.Stopped(byUs = byUs, status = "OK"))
    }

    override fun setMuted(muted: Boolean): Unit = Unit

    override fun setPlaybackMuted(muted: Boolean): Unit = Unit

    override fun chooseOutput(id: String) {
        output = OUTPUTS.firstOrNull { it.id == id } ?: return
        events?.invoke(VoiceDriverEvent.Outputs(OUTPUTS, output))
    }

    override fun listenForScreenShare(listen: Boolean): Unit = Unit

    /** No agent to share with: the share stops as soon as it is asked for. */
    override fun startScreenShare(window: android.view.Window) {
        events?.invoke(VoiceDriverEvent.ScreenShareStopped)
    }

    override fun stopScreenShare(): Unit = Unit

    private companion object {
        val VOLUME_STEP = 200.milliseconds
        val LEVELS = floatArrayOf(1f / 3f, 2f / 3f, 1f, 2f / 3f, 1f, 1f / 3f, 2f / 3f)
        val SPEAKER = VoiceOutput("scripted-speaker", VoiceOutput.Kind.SPEAKER)
        val OUTPUTS = listOf(SPEAKER, VoiceOutput("scripted-earpiece", VoiceOutput.Kind.EARPIECE))
    }
}

/**
 * The timing of a [ConnectVoiceTransport.scripted] call: how long it takes to connect, each side's
 * turn speaking and the pause between them, and when the assistant hangs up (`null`: never, the
 * customer ends it).
 */
public data class VoiceScript(
    val connectAfter: Duration = 1500.milliseconds,
    val assistantTurn: Duration = 3200.milliseconds,
    val customerTurn: Duration = 2400.milliseconds,
    val pause: Duration = 1200.milliseconds,
    val endAfter: Duration? = null,
)
