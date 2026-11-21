package com.samford.doctor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val SamfordBlue = Color(0xFF1A3C8C)
val SamfordBlueLight = Color(0xFFEAF1FB)
val AlertRed = Color(0xFFB3261E)
val SuccessGreen = Color(0xFF1B7F3B)
val WarnAmber = Color(0xFF9A6700)
val Desk = Color(0xFFE4E9F0)

private val colors = lightColorScheme(
    primary = SamfordBlue,
    onPrimary = Color.White,
    primaryContainer = SamfordBlueLight,
    onPrimaryContainer = SamfordBlue,
    secondary = Color(0xFF3F5F90),
    secondaryContainer = Color(0xFFDCE6F7),
    error = AlertRed,
    background = Color(0xFFF7F9FC),
    surface = Color.White,
    surfaceVariant = Color(0xFFEEF2F7),
)

@Composable
fun SamfordTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
