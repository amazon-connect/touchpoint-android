package com.amazon.connect.touchpoint.voice.telecom

import com.amazon.connect.touchpoint.core.ContactState
import com.amazon.connect.touchpoint.core.EndReason
import com.amazon.connect.touchpoint.core.Modality
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The system is told of a voice call as the session has it: begun, active once, gone; never a chat. */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundCallCoordinatorTest {
    private class FakeHost : BackgroundCallHost {
        val calls = mutableListOf<String>()
        var hangUp: (() -> Unit)? = null

        override fun begin(displayName: String, hangUp: () -> Unit) {
            calls += "begin:$displayName"
            this.hangUp = hangUp
        }

        override fun connected() {
            calls += "active"
        }

        override fun end() {
            calls += "end"
        }
    }

    @Test
    fun `a voice call is begun, made active once - a reconnect is not a second - and ended`() =
        runTest(UnconfinedTestDispatcher()) {
            val state = MutableStateFlow<ContactState>(ContactState.Idle)
            val host = FakeHost()
            val c = BackgroundCallCoordinator(state, { Modality.VOICE }, {}, host, "Acme Bank", backgroundScope)
            c.start()
            state.value = ContactState.Connecting
            state.value = ContactState.Connected
            state.value = ContactState.Reconnecting(1)
            state.value = ContactState.Connected
            state.value = ContactState.Ended(EndReason.AGENT_ENDED)
            assertEquals(listOf("begin:Acme Bank", "active", "end"), host.calls)
        }

    @Test
    fun `a chat is never a system call`() =
        runTest(UnconfinedTestDispatcher()) {
            val state = MutableStateFlow<ContactState>(ContactState.Idle)
            val host = FakeHost()
            BackgroundCallCoordinator(state, { Modality.CHAT }, {}, host, "Acme Bank", backgroundScope).start()
            state.value = ContactState.Connecting
            state.value = ContactState.Connected
            assertEquals(emptyList(), host.calls)
        }

    @Test
    fun `the system's hang-up ends the contact, and closing ends the system's call`() =
        runTest(UnconfinedTestDispatcher()) {
            val state = MutableStateFlow<ContactState>(ContactState.Idle)
            val host = FakeHost()
            var ended = 0
            val c = BackgroundCallCoordinator(state, { Modality.VOICE }, { ended++ }, host, "Acme Bank", backgroundScope)
            c.start()
            state.value = ContactState.Connected
            host.hangUp!!()
            assertEquals(1, ended)
            c.close()
            assertEquals(listOf("begin:Acme Bank", "active", "end"), host.calls)
        }
}
