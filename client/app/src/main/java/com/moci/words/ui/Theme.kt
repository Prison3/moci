package com.moci.words.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 与网页版一致的水墨纸质配色
val Paper = Color(0xFFEFE6D6)
val Paper2 = Color(0xFFF7F1E6)
val Ink = Color(0xFF1F1A14)
val InkSoft = Color(0xFF5C5348)
val Line = Color(0xFFD7CBB6)
val Pine = Color(0xFF2C4A3E)
val Pine2 = Color(0xFF3D6656)
val Cinnabar = Color(0xFFB4452A)
val Warn = Color(0xFF9A6B24)

/** 底部导航各 Tab 的强调色 */
val NavHome = Color(0xFF2F7A55)
val NavStudy = Color(0xFF2A7FB5)
val NavRank = Color(0xFFD4922A)
val NavMe = Color(0xFFD4563A)
val NavWords = Color(0xFF3D6BB3)
val NavUsers = Color(0xFF8A4FA3)
val NavLearning = Color(0xFFC97820)

val SerifFamily = FontFamily.Serif

private val MociColors = lightColorScheme(
    primary = Pine,
    onPrimary = Paper2,
    secondary = Pine2,
    onSecondary = Paper2,
    background = Paper,
    onBackground = Ink,
    surface = Paper2,
    onSurface = Ink,
    surfaceVariant = Paper,
    onSurfaceVariant = InkSoft,
    outline = Line,
    error = Cinnabar,
    onError = Paper2,
)

@Composable
fun MociTheme(content: @Composable () -> Unit) {
    // 应用只有浅色纸质风格，不跟随系统深色
    isSystemInDarkTheme()
    MaterialTheme(colorScheme = MociColors) { content() }
}

object MociType {
    val heroNumber = TextStyle(
        fontFamily = SerifFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        color = Pine,
    )
    val cardTerm = TextStyle(
        fontFamily = SerifFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        color = Ink,
    )
}
