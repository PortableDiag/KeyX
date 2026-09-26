package com.keyx.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.keyx.app.theme.KeyboardTheme

/**
 * The settings app wears the keyboard's theme: pick SynthWave for the keyboard
 * and its settings turn SynthWave too. The scheme is derived from the theme's
 * JSON, so a new keyboard theme needs no app code.
 */
fun colorSchemeFor(t: KeyboardTheme): ColorScheme {
    val bg = Color(t.appBackground)
    val dark = bg.luminance() < 0.5f
    val accent = Color(t.accent)
    val onAccent = if (accent.luminance() > 0.4f) Color(t.keyboardBackground).takeIf { dark } ?: Color.Black else Color.White
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = Color(t.keyPressed),
        onPrimaryContainer = Color(t.candidateHighlight),
        secondary = Color(t.functionText),
        onSecondary = onAccent,
        background = bg,
        onBackground = Color(t.keyText),
        surface = bg,
        onSurface = Color(t.keyText),
        surfaceVariant = Color(t.stripBackground),
        onSurfaceVariant = Color(t.candidateText),
        surfaceContainerHigh = Color(t.stripBackground),
        surfaceContainer = Color(t.stripBackground),
        outline = Color(t.keyBorder),
    )
}

@Composable
fun AppTheme(theme: KeyboardTheme, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colorSchemeFor(theme), content = content)
}
