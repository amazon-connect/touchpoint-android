package com.amazon.connect.touchpoint.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class ReconnectionPolicyNanTest {
    @Test
    fun `a NaN or infinite jitter factor, or a NaN draw, yields no jitter rather than an exception`() {
        val base = ReconnectionPolicy(initialDelay = 100.milliseconds)
        assertEquals(100.milliseconds, base.copy(jitterFactor = Double.NaN).delay(1, 0.5))
        assertEquals(100.milliseconds, base.copy(jitterFactor = Double.POSITIVE_INFINITY).delay(1, 0.5))
        assertEquals(100.milliseconds, base.copy(jitterFactor = -1.0).delay(1, 0.5))
        assertEquals(100.milliseconds, base.copy(jitterFactor = 0.5).delay(1, Double.NaN))
    }
}
