package com.amazon.connect.touchpoint.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TouchpointCoreSmokeTest {
    @Test
    fun `module exposes a semantic version`() {
        assertEquals("0.1.0", Touchpoint.VERSION)
    }

    @Test
    fun `the source version matches the build's version`() {
        // Gradle publishes `version` from gradle.properties; the constant is what code can read. The
        // build passes the former in, so the two cannot drift apart unnoticed.
        assertEquals(System.getProperty("touchpoint.version"), Touchpoint.VERSION)
    }
}
