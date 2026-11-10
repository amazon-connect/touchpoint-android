package com.amazon.connect.touchpoint.core

/** Package-wide constants for the Amazon Connect Customer Touchpoint SDK for Android. */
public object Touchpoint {
    /**
     * Semantic version of the SDK. Pre-1.0 is explicitly unstable. Not `const`: a
     * constant is inlined into the host's bytecode, which would report the version it was compiled
     * against rather than the one it runs with.
     */
    public val VERSION: String = "0.1.0"
}
