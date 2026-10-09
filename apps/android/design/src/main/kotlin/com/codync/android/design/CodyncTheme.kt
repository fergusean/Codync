package com.codync.android.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun CodyncTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color.White,
        onPrimary = Color.Black,
        secondary = Color.White,
        onSecondary = Color.Black,
        tertiary = Color.White,
        onTertiary = Color.Black,
        background = Color(0xFF0A0A0A),
        onBackground = Color(0xFFF2F2F2),
        surface = Color(0xFF141414),
        onSurface = Color(0xFFF2F2F2),
        onSurfaceVariant = Color(0xFF9A9A9A),
        surfaceVariant = Color(0xFF1C1C1C),
        surfaceContainer = Color(0xFF1C1C1C),
        surfaceContainerLow = Color(0xFF141414),
        surfaceContainerHigh = Color(0xFF1C1C1C),
        surfaceContainerHighest = Color(0xFF3A3A3A),
        surfaceTint = Color.Transparent,
        error = Color(0xFFF0A7A7),
    ) else lightColorScheme(
        primary = Color.Black,
        onPrimary = Color.White,
        secondary = Color.Black,
        onSecondary = Color.White,
        tertiary = Color.Black,
        onTertiary = Color.White,
        background = Color.White,
        onBackground = Color(0xFF141414),
        surface = Color(0xFFF4F4F4),
        onSurface = Color(0xFF141414),
        onSurfaceVariant = Color(0xFF6B6B6B),
        surfaceVariant = Color(0xFFF0F0F0),
        surfaceContainer = Color(0xFFF0F0F0),
        surfaceContainerLow = Color(0xFFF4F4F4),
        surfaceContainerHigh = Color(0xFFF0F0F0),
        surfaceContainerHighest = Color(0xFFE2E2E2),
        surfaceTint = Color.Transparent,
        error = Color(0xFFC23A2B),
    )
    val type = Typography(
        titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
        bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
    )
    MaterialTheme(colorScheme = colors, typography = type, content = content)
}
