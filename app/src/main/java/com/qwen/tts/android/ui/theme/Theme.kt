package com.qwen.tts.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF145748),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD2EEE3),
    onPrimaryContainer = Color(0xFF133E34),
    secondaryContainer = Color(0xFFE1EBE4),
    onSecondaryContainer = Color(0xFF234B3F),
    background = Color(0xFFF6F5F0),
    onBackground = Color(0xFF18221E),
    surface = Color(0xFFFEFDF8),
    onSurface = Color(0xFF18221E),
    surfaceVariant = Color(0xFFE6E9E1),
    onSurfaceVariant = Color(0xFF49564F),
    surfaceContainer = Color(0xFFEFEEE8),
    surfaceContainerLow = Color(0xFFF6F5EF),
    surfaceContainerHigh = Color(0xFFE9EBE3),
    outline = Color(0xFF6B7970),
    outlineVariant = Color(0xFFCCD5CA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9BD8C3),
    onPrimary = Color(0xFF0C382D),
    primaryContainer = Color(0xFF234E42),
    onPrimaryContainer = Color(0xFFDAF2E9),
    secondaryContainer = Color(0xFF294539),
    onSecondaryContainer = Color(0xFFD7EDDF),
    background = Color(0xFF131A17),
    onBackground = Color(0xFFE5EEE8),
    surface = Color(0xFF19221E),
    onSurface = Color(0xFFE5EEE8),
    surfaceVariant = Color(0xFF283831),
    onSurfaceVariant = Color(0xFFBAC9C0),
    surfaceContainer = Color(0xFF202C26),
    surfaceContainerLow = Color(0xFF19221E),
    surfaceContainerHigh = Color(0xFF25332C),
    outline = Color(0xFF91A69A),
    outlineVariant = Color(0xFF3C5046),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.75).sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
)

private val AppShapes = Shapes(medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(22.dp))

@Composable
fun QwenTtsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

