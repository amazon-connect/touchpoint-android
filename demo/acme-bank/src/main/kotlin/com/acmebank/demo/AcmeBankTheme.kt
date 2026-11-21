package com.acmebank.demo

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The bank's Material theme: brand teal on the system's light or dark surfaces. The partner look is
 * held light, as the partner's web configuration asks (`colorMode: light`), with the system bars' icons
 * dark to match.
 */
@Composable
fun AcmeBankTheme(content: @Composable () -> Unit) {
    val dark = Appearance.dark()
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val scheme =
        if (dark) {
            darkColorScheme(primary = Palette.brandLight, onPrimary = Palette.ink, secondaryContainer = Palette.brandDark, onSecondaryContainer = Color.White)
        } else {
            lightColorScheme(
                primary = Palette.brand,
                onPrimary = Color.White,
                secondaryContainer = Palette.sand,
                onSecondaryContainer = Palette.ink,
                onSurface = Palette.ink,
                onSurfaceVariant = Palette.muted,
            )
        }
    MaterialTheme(colorScheme = scheme, content = content)
}
