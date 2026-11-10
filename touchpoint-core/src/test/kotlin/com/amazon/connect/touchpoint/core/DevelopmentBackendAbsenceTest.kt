package com.amazon.connect.touchpoint.core

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * DevelopmentBackend accepts literal keys and must never ship. A tripwire, not the guarantee: it
 * fails if the class is ever moved into Core or Core's test classpath gains the development module.
 * The guarantees are the graph and release-symbol gates over what we publish, and a host declaring
 * the module with `debugImplementation` — which nothing in this repository can enforce.
 */
class DevelopmentBackendAbsenceTest {
    @Test
    fun `DevelopmentBackend is not on Core's classpath`() {
        assertFailsWith<ClassNotFoundException> { Class.forName("com.amazon.connect.touchpoint.development.DevelopmentBackend") }
    }
}
