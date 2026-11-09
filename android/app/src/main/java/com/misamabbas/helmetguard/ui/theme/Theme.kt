package com.misamabbas.helmetguard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Status {
    val Helmet = Color(0xFF12864B)
    val NoHelmet = Color(0xFFD92D20)
    val Undecided = Color(0xFFB76E00)
    val Plate = Color(0xFF2A78D6)
    val Live = Color(0xFF2A78D6)
    val Video = Color(0xFFEB6834)
}

private val Light = lightColorScheme(
    primary = Color(0xFF1B1B18),
    onPrimary = Color(0xFFFCFCFB),
    secondaryContainer = Color(0xFFE7E6E0),
    onSecondaryContainer = Color(0xFF1B1B18),
    background = Color(0xFFF4F3EF),
    onBackground = Color(0xFF1B1B18),
    surface = Color(0xFFFCFCFB),
    onSurface = Color(0xFF1B1B18),
    surfaceVariant = Color(0xFFEFEEE9),
    onSurfaceVariant = Color(0xFF52514E),
    outline = Color(0xFFD6D5CE),
    outlineVariant = Color(0xFFE0DFD8),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFF0EFEC),
    onPrimary = Color(0xFF1A1A19),
    secondaryContainer = Color(0xFF33332F),
    onSecondaryContainer = Color(0xFFF0EFEC),
    background = Color(0xFF121211),
    onBackground = Color(0xFFF0EFEC),
    surface = Color(0xFF1A1A19),
    onSurface = Color(0xFFF0EFEC),
    surfaceVariant = Color(0xFF252523),
    onSurfaceVariant = Color(0xFFC3C2B7),
    outline = Color(0xFF45453F),
    outlineVariant = Color(0xFF33332F),
    error = Color(0xFFF2998F),
)

@Composable
fun HelmetGuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
