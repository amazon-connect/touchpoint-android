package com.amazon.connect.touchpoint.voice

import com.amazon.connect.touchpoint.core.EndReason
import com.amazon.connect.touchpoint.core.TransportEvent
import com.amazon.connect.touchpoint.core.VoiceContactCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The scripted call through the real transport: what a recording shows. */
class ScriptedVoiceDriverTest {
    private val placeholder = VoiceContactCredentials("offline", "customer", "offline", "{}", "{}", null)

    private fun transport(script: VoiceScript) =
        ConnectVoiceTransport(ScriptedVoiceDriver(script, CoroutineScope(SupervisorJob() + Dispatchers.Default)), { true }, Dispatchers.Unconfined)

    private val quick = VoiceScript(connectAfter = 20.milliseconds, assistantTurn = 700.milliseconds, customerTurn = 700.milliseconds, pause = 20.milliseconds)

    @Test
    fun `it connects, then the assistant and the customer take turns speaking`() =
        runBlocking {
            val t = transport(quick)
            t.connect(placeholder)
            assertEquals(TransportEvent.Connected, withTimeout(2.seconds) { t.events.first() })
            withTimeout(2.seconds) { t.call.first { it.agentSpeaking } }
            withTimeout(3.seconds) { t.call.first { it.userSpeaking && !it.agentSpeaking } }
            assertEquals(VoiceOutput.Kind.SPEAKER, t.call.value.output?.kind)
            t.disconnect()
        }

    @Test
    fun `muted, the customer is never heard speaking`() =
        runBlocking {
            val t = transport(quick)
            t.connect(placeholder)
            t.setMuted(true)
            withTimeout(2.seconds) { t.events.first() }
            assertNull(withTimeoutOrNull(2.seconds) { t.call.first { it.userSpeaking } })
            t.disconnect()
        }

    @Test
    fun `the assistant hangs up when the script says, and the customer's own hang-up is no agent end`() =
        runBlocking {
            val ends = transport(quick.copy(endAfter = 100.milliseconds))
            ends.connect(placeholder)
            assertEquals(TransportEvent.Connected, withTimeout(2.seconds) { ends.events.first() })
            assertEquals(TransportEvent.ContactEnded(EndReason.AGENT_ENDED), withTimeout(2.seconds) { ends.events.first() })

            val ours = transport(quick)
            ours.connect(placeholder)
            withTimeout(2.seconds) { ours.events.first() }
            ours.disconnect()
            delay(100.milliseconds)
            assertNull(withTimeoutOrNull(300.milliseconds) { ours.events.first() })
            assertTrue(!ours.call.value.agentSpeaking && !ours.call.value.userSpeaking)
        }
}
