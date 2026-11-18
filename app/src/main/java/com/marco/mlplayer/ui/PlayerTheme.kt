package com.marco.mlplayer.ui

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import com.marco.mlplayer.data.Covers
import com.marco.mltheme.LocalSwitchStyle
import com.marco.mltheme.MLThemeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Colore principale di una copertina: il più vivace, altrimenti il più presente. */
private fun coverSeed(bmp: Bitmap): Int? {
    val p = Palette.from(bmp).maximumColorCount(16).generate()
    val swatch = p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.darkVibrantSwatch ?: p.dominantSwatch
    return swatch?.rgb
}

/**
 * Tema Material 3 dell'app (MLTheme): chiaro/scuro e colori scelti nelle impostazioni.
 * Con "Colori dalla copertina" acceso, i colori seguono la copertina del brano in riproduzione.
 */
@Composable
fun PlayerTheme(env: AppEnv, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val ml = MLThemeSettings.get(ctx)
    val track = env.engine.currentTrack
    val useCover = env.settings.coverColors
    val coverFile = if (useCover && track != null) env.library.trackCoverFile(track) else null

    val seed by produceState<Int?>(null, coverFile?.path, env.library.revision) {
        value = if (coverFile == null) null else withContext(Dispatchers.IO) {
            runCatching { Covers.load(coverFile, 160)?.let { coverSeed(it) } }.getOrNull()
        }
    }

    val scheme = animatedScheme(cyberScheme(seed))
    CompositionLocalProvider(LocalSwitchStyle provides ml.switchStyle) {
        MaterialTheme(colorScheme = scheme, typography = cyberTypography, content = content)
    }
}

/** Forma "tagliata" in diagonale, usata da schede, copertine ed etichette. */
val CyberShape = androidx.compose.foundation.shape.CutCornerShape(topStart = 0.dp, topEnd = 14.dp, bottomEnd = 0.dp, bottomStart = 14.dp)

/** Titoli, etichette e tempi in un carattere tecnico a spaziatura fissa. */
private val cyberTypography: Typography = Typography().let { t ->
    val m = FontFamily.Monospace
    t.copy(
        headlineLarge = t.headlineLarge.copy(fontFamily = m), headlineMedium = t.headlineMedium.copy(fontFamily = m),
        headlineSmall = t.headlineSmall.copy(fontFamily = m), titleLarge = t.titleLarge.copy(fontFamily = m),
        titleMedium = t.titleMedium.copy(fontFamily = m), titleSmall = t.titleSmall.copy(fontFamily = m),
        labelLarge = t.labelLarge.copy(fontFamily = m), labelMedium = t.labelMedium.copy(fontFamily = m),
        labelSmall = t.labelSmall.copy(fontFamily = m),
    )
}

/** Colore neon: tinta [seed] (o ciano) con saturazione e luminosità al massimo, ruotata di [shift] gradi. */
private fun neon(seed: Int?, shift: Float = 0f, v: Float = 1f): Color {
    val hsv = FloatArray(3)
    if (seed == null) android.graphics.Color.colorToHSV(0xFF00E5FF.toInt(), hsv) else android.graphics.Color.colorToHSV(seed, hsv)
    hsv[0] = (hsv[0] + shift) % 360f
    // i grigi della copertina non hanno una tinta: in quel caso si parte dal ciano
    if (seed != null && hsv[1] < 0.15f) android.graphics.Color.colorToHSV(0xFF00E5FF.toInt(), hsv)
    hsv[1] = 0.85f; hsv[2] = v
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** Tema cyberpunk: sfondo quasi nero e colori neon presi dalla copertina (ciano se non c'è). */
private fun cyberScheme(seed: Int?): ColorScheme {
    val p = neon(seed)
    val s = neon(seed, 150f)
    val t = neon(seed, 60f)
    val bg = Color(0xFF07060D)
    return darkColorScheme(
        primary = p, onPrimary = Color.Black, primaryContainer = neon(seed, 0f, 0.32f), onPrimaryContainer = p,
        secondary = s, onSecondary = Color.Black, secondaryContainer = neon(seed, 150f, 0.3f), onSecondaryContainer = s,
        tertiary = t, onTertiary = Color.Black, tertiaryContainer = neon(seed, 60f, 0.3f), onTertiaryContainer = t,
        background = bg, onBackground = Color(0xFFE6F6FF), surface = bg, onSurface = Color(0xFFE6F6FF),
        surfaceVariant = Color(0xFF17142A), onSurfaceVariant = Color(0xFFA9B6D6),
        surfaceContainerLowest = Color(0xFF040309), surfaceContainerLow = Color(0xFF0D0B18), surfaceContainer = Color(0xFF121021),
        surfaceContainerHigh = Color(0xFF181529), surfaceContainerHighest = Color(0xFF1F1B34),
        outline = p.copy(alpha = 0.55f), outlineVariant = p.copy(alpha = 0.25f),
    )
}

/** Passaggio morbido tra i colori di una copertina e quelli della successiva. */
@Composable
private fun animatedScheme(t: ColorScheme): ColorScheme {
    @Composable
    fun a(c: Color): Color = animateColorAsState(c, tween(600), label = "tema").value
    return t.copy(
        primary = a(t.primary), onPrimary = a(t.onPrimary),
        primaryContainer = a(t.primaryContainer), onPrimaryContainer = a(t.onPrimaryContainer),
        secondary = a(t.secondary), onSecondary = a(t.onSecondary),
        secondaryContainer = a(t.secondaryContainer), onSecondaryContainer = a(t.onSecondaryContainer),
        tertiary = a(t.tertiary), onTertiary = a(t.onTertiary),
        tertiaryContainer = a(t.tertiaryContainer), onTertiaryContainer = a(t.onTertiaryContainer),
        background = a(t.background), onBackground = a(t.onBackground),
        surface = a(t.surface), onSurface = a(t.onSurface),
        surfaceVariant = a(t.surfaceVariant), onSurfaceVariant = a(t.onSurfaceVariant),
        surfaceContainerLowest = a(t.surfaceContainerLowest), surfaceContainerLow = a(t.surfaceContainerLow),
        surfaceContainer = a(t.surfaceContainer), surfaceContainerHigh = a(t.surfaceContainerHigh),
        surfaceContainerHighest = a(t.surfaceContainerHighest),
        outline = a(t.outline), outlineVariant = a(t.outlineVariant),
    )
}
