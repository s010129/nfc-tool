package io.github.s010129.nfctool

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF0F766E), onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1), onPrimaryContainer = Color(0xFF134E4A),
    secondary = Color(0xFF475569), secondaryContainer = Color(0xFFE2E8F0),
    error = Color(0xFFB91C1C), errorContainer = Color(0xFFFEE2E2), onErrorContainer = Color(0xFF7F1D1D),
    background = Color(0xFFF6F8F8), surface = Color(0xFFF6F8F8),
    surfaceContainerLow = Color.White, surfaceContainer = Color(0xFFEEF2F2),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF5EEAD4), onPrimary = Color(0xFF042F2E),
    primaryContainer = Color(0xFF115E59), onPrimaryContainer = Color(0xFFCCFBF1),
    error = Color(0xFFFCA5A5), errorContainer = Color(0xFF7F1D1D), onErrorContainer = Color(0xFFFEE2E2),
    background = Color(0xFF0B1514), surface = Color(0xFF0B1514),
    surfaceContainerLow = Color(0xFF12201F), surfaceContainer = Color(0xFF172827),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
