package com.lazyrotate.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/*
 * Material 3 主题（Compose 版，不依赖 material-components 的 View 版本）。
 * - Android 12+ 用系统动态取色（dynamicLight/DarkColorScheme）
 * - 低版本用固定品牌配色
 * - 跟随系统深色模式
 */

private val LazyPrimitive = Color(0xFF0B6E99)
private val LazyPrimitiveContainer = Color(0xFFCBE6F5)
private val LazySecondary = Color(0xFF1E7F73)
private val LazySecondaryContainer = Color(0xFFB8ECE2)
private val LazyTertiary = Color(0xFFB26A00)
private val LazyTertiaryContainer = Color(0xFFFFDDB3)

private val LightColors = lightColorScheme(
    primary = LazyPrimitive,
    onPrimary = Color.White,
    primaryContainer = LazyPrimitiveContainer,
    onPrimaryContainer = Color(0xFF001E2C),
    secondary = LazySecondary,
    onSecondary = Color.White,
    secondaryContainer = LazySecondaryContainer,
    onSecondaryContainer = Color(0xFF00201B),
    tertiary = LazyTertiary,
    onTertiary = Color.White,
    tertiaryContainer = LazyTertiaryContainer,
    onTertiaryContainer = Color(0xFF2A1800),
    background = Color(0xFFF7FAFD),
    onBackground = Color(0xFF191C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C1E),
    surfaceVariant = Color(0xFFDCE3E8),
    onSurfaceVariant = Color(0xFF41484D),
    outline = Color(0xFF71787E),
    outlineVariant = Color(0xFFC1C8CD),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD1F5),
    onPrimary = Color(0xFF00344A),
    primaryContainer = Color(0xFF004C6A),
    onPrimaryContainer = LazyPrimitiveContainer,
    secondary = Color(0xFF9BD4C9),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF005047),
    onSecondaryContainer = LazySecondaryContainer,
    tertiary = Color(0xFFF7BD72),
    onTertiary = Color(0xFF472A00),
    tertiaryContainer = Color(0xFF653E00),
    onTertiaryContainer = LazyTertiaryContainer,
    background = Color(0xFF0F1417),
    onBackground = Color(0xFFDFE3E6),
    surface = Color(0xFF171C1F),
    onSurface = Color(0xFFDFE3E6),
    surfaceVariant = Color(0xFF41484D),
    onSurfaceVariant = Color(0xFFC1C8CD),
    outline = Color(0xFF8B9297),
    outlineVariant = Color(0xFF41484D),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun LazyRotateTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
