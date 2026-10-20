package com.digup.android.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val Teal = Color(0xFF0F3D3E)
private val TealLight = Color(0xFF2A6465)
private val Sand = Color(0xFFB0642E)
private val Cream = Color(0xFFF7F2E8)

private val LightScheme = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE8E7),
    onPrimaryContainer = Color(0xFF062223),
    secondary = Sand,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDCC4),
    onSecondaryContainer = Color(0xFF351000),
    background = Cream,
    onBackground = Color(0xFF1B1C19),
    surface = Color(0xFFFFFBF4),
    onSurface = Color(0xFF1B1C19),
    surfaceVariant = Color(0xFFE7E1D6),
    onSurfaceVariant = Color(0xFF474743),
    outline = Color(0xFF77766F),
    error = Color(0xFFB3261E),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9BD4D3),
    onPrimary = Color(0xFF003738),
    primaryContainer = Color(0xFF0F4F50),
    onPrimaryContainer = Color(0xFFB9ECEB),
    secondary = Color(0xFFFFB68A),
    onSecondary = Color(0xFF542106),
    secondaryContainer = Color(0xFF733512),
    onSecondaryContainer = Color(0xFFFFDCC4),
    background = Color(0xFF111412),
    onBackground = Color(0xFFE4E3DD),
    surface = Color(0xFF171A18),
    onSurface = Color(0xFFE4E3DD),
    surfaceVariant = Color(0xFF454844),
    onSurfaceVariant = Color(0xFFC5C7C1),
    outline = Color(0xFF8F918B),
    error = Color(0xFFFFB4AB),
)

@Composable
fun DigUpTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) DarkScheme else LightScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
