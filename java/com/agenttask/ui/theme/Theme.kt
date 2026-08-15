package com.agenttask.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.agenttask.data.ThemeMode

private val Accent = Color(0xFF10A37F)

private val Dark = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Color(0xFF0E0F13),
    onBackground = Color(0xFFECECF1),
    surface = Color(0xFF15171C),
    onSurface = Color(0xFFECECF1),
    surfaceVariant = Color(0xFF22252C),
    onSurfaceVariant = Color(0xFFC5C8D0),
    outline = Color(0xFF3A3F49)
)

private val Light = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF16181D),
    surface = Color(0xFFF7F7F8),
    onSurface = Color(0xFF16181D),
    surfaceVariant = Color(0xFFECECF1),
    onSurfaceVariant = Color(0xFF41454D),
    outline = Color(0xFFD3D5DB)
)

val CodeStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)

@Composable
fun AgentTaskTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
