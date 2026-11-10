package com.amazon.connect.touchpoint.voice

import com.amazon.connect.touchpoint.core.Touchpoint
import kotlin.test.Test
import kotlin.test.assertEquals

class TouchpointVoiceSmokeTest {
    @Test
    fun `module links against Core and reports the shared version`() {
        assertEquals(Touchpoint.VERSION, TouchpointVoice.VERSION)
    }
}
