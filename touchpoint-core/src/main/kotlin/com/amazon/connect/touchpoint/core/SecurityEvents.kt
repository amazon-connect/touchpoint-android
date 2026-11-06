package com.amazon.connect.touchpoint.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Touchpoint's API shared by its own modules; not for hosts. */
@RequiresOptIn(message = "Touchpoint's internal API, shared by its own modules; not for hosts.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
public annotation class InternalTouchpointApi

/**
 * A security decision the SDK made on the device, for the host's own monitoring: what was
 * refused, blocked or changed, never the conversation. No words, no tokens, no links — an action's
 * name is the host's own identifier. The SDK sends none of these anywhere; a host that wants them in
 * its telemetry collects [TouchpointSecurityEvents.events].
 */
public sealed class TouchpointSecurityEvent {
    /** A sign-in came back on the host's scheme but not as Connect sent it out (scheme, host, path or `state`): refused. */
    public data object SignInRedirectRejected : TouchpointSecurityEvent()

    /** A guide's instance URL was not an `https` Amazon Connect instance: the renderer was not loaded. */
    public data object GuideInstanceRefused : TouchpointSecurityEvent()

    /** The guide's page tried to navigate, which it never may: stopped. */
    public data object GuideNavigationBlocked : TouchpointSecurityEvent()

    /** A Live Sync invocation of a `confirmationRequired` action that did not run: refused, replaced, or withdrawn. */
    public data class LiveSyncActionDeclined(val action: String) : TouchpointSecurityEvent()

    /** A Live Sync invocation the device refused: not advertised, or a payload its declared schema does not allow. */
    public data class LiveSyncPayloadRejected(val action: String) : TouchpointSecurityEvent()

    /** A Live Sync invocation of a name nothing on screen declares: a stale context, or an engine asking for what it was not offered. */
    public data class LiveSyncActionUnrouted(val action: String) : TouchpointSecurityEvent()

    /** The customer's Live Sync consent: [granted] `true` when they allowed sharing, `false` when they declined or stopped. */
    public data class LiveSyncConsentChanged(val granted: Boolean) : TouchpointSecurityEvent()

    /** The app's window began going to the agent as a screen share. */
    public data object ScreenShareStarted : TouchpointSecurityEvent()

    /** The screen share ended. */
    public data object ScreenShareStopped : TouchpointSecurityEvent()
}

/**
 * Every [TouchpointSecurityEvent], process-wide, as it happens. Hot and never replayed: collect it
 * from start-up for a full record. A slow collector loses the oldest, never blocks the SDK.
 */
public object TouchpointSecurityEvents {
    private val mutableEvents = MutableSharedFlow<TouchpointSecurityEvent>(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    public val events: SharedFlow<TouchpointSecurityEvent> = mutableEvents.asSharedFlow()

    @InternalTouchpointApi
    public fun report(event: TouchpointSecurityEvent) {
        mutableEvents.tryEmit(event)
    }

    private const val BUFFER = 64
}
