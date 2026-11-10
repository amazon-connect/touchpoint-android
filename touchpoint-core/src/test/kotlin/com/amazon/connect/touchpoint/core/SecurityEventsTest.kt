@file:OptIn(InternalTouchpointApi::class)

package com.amazon.connect.touchpoint.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The SDK's security decisions, for the host's own monitoring, and none of the conversation. */
@OptIn(ExperimentalCoroutinesApi::class)
class SecurityEventsTest {
    @Test
    fun `a host collecting hears each event as it happens, and a report never waits on it`() =
        runTest {
            val heard = mutableListOf<TouchpointSecurityEvent>()
            val watching = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { TouchpointSecurityEvents.events.collect { heard += it } }
            TouchpointSecurityEvents.report(TouchpointSecurityEvent.GuideInstanceRefused)
            TouchpointSecurityEvents.report(TouchpointSecurityEvent.LiveSyncConsentChanged(granted = false))
            assertEquals(listOf(TouchpointSecurityEvent.GuideInstanceRefused, TouchpointSecurityEvent.LiveSyncConsentChanged(false)), heard)
            watching.cancel()
            // Nobody listening, or far behind: reporting still returns at once.
            repeat(1_000) { TouchpointSecurityEvents.report(TouchpointSecurityEvent.ScreenShareStopped) }
        }

    @Test
    fun `an event names what happened, never a token, a link or a word of the conversation`() {
        val printed = listOf(TouchpointSecurityEvent.SignInRedirectRejected, TouchpointSecurityEvent.LiveSyncActionDeclined("submit_dispute")).joinToString()
        assertFalse("http" in printed || "state=" in printed, printed)
    }
}
