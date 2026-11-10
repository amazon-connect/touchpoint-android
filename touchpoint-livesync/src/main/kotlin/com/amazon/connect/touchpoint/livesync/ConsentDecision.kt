package com.amazon.connect.touchpoint.livesync

/**
 * Whether the customer has agreed to Live Sync sharing what the app is doing.
 *
 * **No context POST happens before [GRANTED]** — not the first advertisement, not a replay on
 * reconnect. The default is [HELD]: nothing has been decided, no context is sent. [DENIED] leaves chat
 * and voice fully functional and Live Sync silent. The gate lives where the context path is built, so
 * no later layer can establish a post-freely pattern.
 *
 * The decision covers sharing, not the actions: as on Touchpoint for the web, an action the caller
 * asks for runs through what the current screen declares. A host that wants no action before the
 * grant keeps the socket closed until then (`autoConnect = false`).
 *
 * There is no `development` constant: a library cannot see the host's build type, so a
 * debug convenience here would ship in release. A host passes [GRANTED] from its own debug code.
 */
public enum class ConsentDecision {
    /** Not yet decided. Context is held, not posted. */
    HELD,
    GRANTED,
    DENIED,
    ;

    public companion object {
        /** What a client starts with when nothing is specified: held. */
        public val DEFAULT: ConsentDecision = HELD
    }
}
