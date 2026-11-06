package com.amazon.connect.touchpoint.core.android

import android.content.Context

/**
 * Typed access to the SDK's string resources (`res/values/strings.xml`). Every user-facing string the
 * SDK renders goes through here, so localisation is a resource edit, never a code change.
 *
 * Internal until a second module needs it: every public symbol becomes immutable at publication.
 */
internal object TouchpointStrings {
    /** Placeholder entry proving the resources are packaged and resolvable. */
    fun placeholder(context: Context): String = context.getString(R.string.touchpoint_placeholder)
}
