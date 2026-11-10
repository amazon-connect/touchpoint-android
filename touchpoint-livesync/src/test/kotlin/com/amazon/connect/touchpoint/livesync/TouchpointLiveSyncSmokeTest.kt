package com.amazon.connect.touchpoint.livesync

import com.amazon.connect.touchpoint.core.Touchpoint
import kotlin.test.Test
import kotlin.test.assertEquals

class TouchpointLiveSyncSmokeTest {
    @Test
    fun `module links against Core and reports the shared version`() {
        assertEquals(Touchpoint.VERSION, TouchpointLiveSync.VERSION)
    }
}
