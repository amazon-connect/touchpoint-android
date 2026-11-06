package com.amazon.connect.touchpoint.chat.ui

/**
 * The UI kit's own internals — controls, sizes and locals shared by the SDK's chat and voice modules.
 * Not for hosts: no stability promise, and an opt-in so a host cannot use one by accident.
 */
@RequiresOptIn(message = "Touchpoint's internal UI API, shared by its own modules; not for hosts.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.TYPEALIAS)
public annotation class InternalTouchpointUi
