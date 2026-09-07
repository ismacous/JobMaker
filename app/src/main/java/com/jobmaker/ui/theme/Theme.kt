package com.jobmaker.ui.theme

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

/**
 * Palette volontairement sobre : l'application sert a produire des documents
 * professionnels, l'interface ne doit pas distraire. Pas de couleur dynamique
 * du systeme, pour que les apercus de CV s'affichent toujours sur le meme fond.
 */
private val BleuMarine = Color(0xFF1F4E79)
private val BleuClair = Color(0xFF4A7FB5)
private val Ardoise = Color(0xFF2F3A45)
private val Vert = Color(0xFF0F766E)
private val Ambre = Color(0xFFB45309)
private val Rouge = Color(0xFFB3261E)

private val Clair = lightColorScheme(
    primary = BleuMarine,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E6F2),
    onPrimaryContainer = Color(0xFF0C2D4C),
    secondary = Vert,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3EDE9),
    onSecondaryContainer = Color(0xFF06332F),
    tertiary = Ambre,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFDEBD2),
    onTertiaryContainer = Color(0xFF4A2400),
    error = Rouge,
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF16191D),
    surface = Color.White,
    onSurface = Color(0xFF16191D),
    surfaceVariant = Color(0xFFEBEEF2),
    onSurfaceVariant = Color(0xFF474F58),
    outline = Color(0xFFB9C0C8),
    outlineVariant = Color(0xFFDDE2E7),
)

private val Sombre = darkColorScheme(
    primary = BleuClair,
    onPrimary = Color(0xFF0A1F33),
    primaryContainer = Color(0xFF1B3A57),
    onPrimaryContainer = Color(0xFFD3E4F5),
    secondary = Color(0xFF5EC4B8),
    onSecondary = Color(0xFF00332E),
    tertiary = Color(0xFFE9A94B),
    onTertiary = Color(0xFF3B2200),
    error = Color(0xFFF2B8B5),
    background = Color(0xFF121417),
    onBackground = Color(0xFFE3E5E8),
    surface = Color(0xFF1A1D21),
    onSurface = Color(0xFFE3E5E8),
    surfaceVariant = Color(0xFF2A2E33),
    onSurfaceVariant = Color(0xFFC2C7CD),
    outline = Color(0xFF5A6169),
    outlineVariant = Color(0xFF3A3F45),
)

private val typographie = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 25.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp),
    bodyLarge = TextStyle(fontSize = 15.5.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun JobMakerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Sombre else Clair,
        typography = typographie,
        content = content,
    )
}

val CouleurSucces = Color(0xFF0F766E)
val CouleurAlerte = Color(0xFFB45309)
