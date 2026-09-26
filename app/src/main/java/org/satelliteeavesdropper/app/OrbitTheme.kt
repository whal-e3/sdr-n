package org.satelliteeavesdropper.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal object OrbitColors {
    val background = Color(0xFF07131F)
    val surface = Color(0xFF102434)
    val surfaceRaised = Color(0xFF183244)
    val cyan = Color(0xFF67DDD7)
    val amber = Color(0xFFFFC979)
    val blue = Color(0xFF9DB9FF)
    val muted = Color(0xFF9FB4C2)
    val red = Color(0xFFFFA6A0)
    val white = Color(0xFFEAF5F8)
}

private val orbitScheme = darkColorScheme(
    primary = OrbitColors.cyan,
    onPrimary = Color(0xFF062B2D),
    primaryContainer = Color(0xFF17484D),
    onPrimaryContainer = OrbitColors.white,
    secondary = OrbitColors.amber,
    onSecondary = Color(0xFF3E2910),
    tertiary = OrbitColors.blue,
    background = OrbitColors.background,
    onBackground = OrbitColors.white,
    surface = OrbitColors.surface,
    onSurface = OrbitColors.white,
    surfaceVariant = OrbitColors.surfaceRaised,
    onSurfaceVariant = OrbitColors.muted,
    outline = Color(0xFF567184),
    error = OrbitColors.red,
)

@Composable
internal fun OrbitTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = orbitScheme, content = content)
}
