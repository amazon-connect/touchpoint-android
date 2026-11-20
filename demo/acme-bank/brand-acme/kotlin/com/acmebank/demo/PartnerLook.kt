package com.acmebank.demo

import android.content.Context
import androidx.compose.runtime.Composable
import com.amazon.connect.touchpoint.chat.ui.TouchpointChatOptions
import com.amazon.connect.touchpoint.chat.ui.TouchpointTheme

/**
 * No brand folder: no partner look. A brand folder (`partner/`) can carry one, a whole set of chat
 * options in a partner's style, offered as a switch in Settings.
 */
object PartnerLook {
    const val AVAILABLE: Boolean = false

    var on: Boolean
        get() = false
        set(@Suppress("UNUSED_PARAMETER") value) = Unit

    fun restore(@Suppress("UNUSED_PARAMETER") context: Context) = Unit

    val theme: TouchpointTheme get() = TouchpointTheme.Light

    val darkTheme: TouchpointTheme get() = TouchpointTheme.Dark

    @Composable
    fun options(): TouchpointChatOptions = TouchpointChatOptions()
}
