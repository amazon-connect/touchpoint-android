package com.acmebank.demo

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Settings → Appearance: the app's own light or dark, over the system's, as many apps offer. System
 * follows the phone, except that the partner bank look keeps its configured light; Light and Dark
 * hold, whatever the phone or the look. Kept across launches, as the partner look is.
 */
object Appearance {
    enum class Mode { SYSTEM, LIGHT, DARK }

    private var prefs: android.content.SharedPreferences? = null
    private var state by mutableStateOf(Mode.SYSTEM)

    var mode: Mode
        get() = state
        set(value) {
            state = value
            prefs?.edit()?.putString(KEY, value.name)?.apply()
        }

    fun restore(context: Context) {
        prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        state = prefs?.getString(KEY, null)?.let { saved -> Mode.entries.firstOrNull { it.name == saved } } ?: Mode.SYSTEM
    }

    /** Whether the app draws dark now. */
    @Composable
    fun dark(): Boolean =
        when (mode) {
            Mode.LIGHT -> false
            Mode.DARK -> true
            Mode.SYSTEM -> isSystemInDarkTheme() && !PartnerLook.on
        }

    private const val KEY = "mode"
}
