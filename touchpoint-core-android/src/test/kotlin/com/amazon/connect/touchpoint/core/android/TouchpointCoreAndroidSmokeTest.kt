package com.amazon.connect.touchpoint.core.android

import com.amazon.connect.touchpoint.core.Touchpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TouchpointCoreAndroidSmokeTest {
    @Test
    fun `module links against Core`() {
        assertEquals("0.1.0", Touchpoint.VERSION)
    }

    @Test
    fun `the string resources are linked into the module`() {
        // Resolution needs a Context, which the local unit tier does not have; the instrumented tier
        // resolves the value. Here the generated R field proves the resource compiled and linked.
        assertNotEquals(0, R.string.touchpoint_placeholder)
    }
}
