package com.xiaosiqi.quizbank.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

val BrandBlue = Color(0xFF2563EB)
val BrandBlueDark = Color(0xFF1D4ED8)
val BrandGreen = Color(0xFF16A34A)
val BrandRed = Color(0xFFDC2626)
val BrandAmber = Color(0xFFD97706)

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF0B2A6B),
    secondary = Color(0xFF475569),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E8F0),
    onSecondaryContainer = Color(0xFF1E293B),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF111827),
    surface = Color.White,
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFEDF0F6),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    error = BrandRed,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF93B4F7),
    onPrimary = Color(0xFF0B2A6B),
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFF94A3B8),
    onSecondary = Color(0xFF0F172A),
    secondaryContainer = Color(0xFF334155),
    onSecondaryContainer = Color(0xFFE2E8F0),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE5E7EB),
    surface = Color(0xFF171C22),
    onSurface = Color(0xFFE5E7EB),
    surfaceVariant = Color(0xFF232A33),
    onSurfaceVariant = Color(0xFFB6C0CC),
    outline = Color(0xFF3F4A57),
    error = Color(0xFFF87171),
)

private fun typographyFor(scale: Float): Typography {
    val base = Typography()
    fun TextStyle.scaled() = copy(
        fontSize = fontSize * scale,
        lineHeight = lineHeight * scale,
    )
    return Typography(
        displayLarge = base.displayLarge.scaled(),
        displayMedium = base.displayMedium.scaled(),
        displaySmall = base.displaySmall.scaled(),
        headlineLarge = base.headlineLarge.scaled(),
        headlineMedium = base.headlineMedium.scaled(),
        headlineSmall = base.headlineSmall.scaled(),
        titleLarge = base.titleLarge.scaled().copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.scaled().copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.scaled(),
        bodyLarge = base.bodyLarge.scaled().copy(fontSize = 17.sp * scale, lineHeight = 26.sp * scale),
        bodyMedium = base.bodyMedium.scaled(),
        bodySmall = base.bodySmall.scaled(),
        labelLarge = base.labelLarge.scaled(),
        labelMedium = base.labelMedium.scaled(),
        labelSmall = base.labelSmall.scaled(),
    )
}

@Composable
fun QuizBankTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    // 让 LocalContext 被引用，避免某些编译器对未使用参数的告警
    MaterialTheme(
        colorScheme = colors,
        typography = typographyFor(fontScale.coerceIn(0.8f, 1.6f)),
        content = content,
    )
}
