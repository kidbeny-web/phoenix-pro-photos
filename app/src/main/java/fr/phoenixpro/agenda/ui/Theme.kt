package fr.phoenixpro.agenda.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Couleurs du logo Phoenix Pro (identiques à Phoenix Pro Photos)
val PhoenixBlue = Color(0xFF1D63C4)
val PhoenixOrange = Color(0xFFF08A1C)
val PhoenixPurple = Color(0xFF7B3FBF)
val PhoenixTeal = Color(0xFF1BA39C)

private val Light = lightColorScheme(
    primary = PhoenixBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8F0FB),
    onPrimaryContainer = Color(0xFF0E3A78),
    secondary = PhoenixOrange,
    onSecondary = Color.White,
    tertiary = PhoenixPurple,
    background = Color.White,
    onBackground = Color(0xFF111417),
    surface = Color.White,
    onSurface = Color(0xFF111417),
    surfaceVariant = Color(0xFFF5F6F7),
    onSurfaceVariant = Color(0xFF4D5459),
    surfaceContainer = Color(0xFFF5F6F7),
    surfaceContainerHigh = Color(0xFFEEF0F2),
    surfaceContainerLow = Color(0xFFF8F9FA),
    outline = Color(0xFFD9DCDF),
    outlineVariant = Color(0xFFECEEF0),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF6F9BFF),
    onPrimary = Color(0xFF0B1E44),
    primaryContainer = Color(0xFF1A2440),
    onPrimaryContainer = Color(0xFFD8E3FF),
    secondary = PhoenixOrange,
    tertiary = Color(0xFFB48CF0),
    background = Color(0xFF0E1012),
    onBackground = Color(0xFFF1F3F4),
    surface = Color(0xFF0E1012),
    onSurface = Color(0xFFF1F3F4),
    surfaceVariant = Color(0xFF181B1E),
    onSurfaceVariant = Color(0xFFB4B9BD),
    surfaceContainer = Color(0xFF181B1E),
    surfaceContainerHigh = Color(0xFF202427),
    surfaceContainerLow = Color(0xFF131618),
    outline = Color(0xFF3A3F44),
    outlineVariant = Color(0xFF23272B),
)

private val Type = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 26.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.sp),
)

@Composable
fun AgendaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Type, content = content)
}

/** Rouge des jours fériés. */
val HolidayRed = Color(0xFFD93F3F)
/** Teinte des jours de vacances scolaires. */
val SchoolTint = Color(0x26F08A1C)
