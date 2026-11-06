package com.amazon.connect.touchpoint.chat

import com.amazon.connect.touchpoint.core.Touchpoint
import kotlin.test.Test
import kotlin.test.assertEquals

class TouchpointChatSmokeTest {
    @Test
    fun `module links against Core and reports the shared version`() {
        assertEquals(Touchpoint.VERSION, TouchpointChat.VERSION)
    }
}
