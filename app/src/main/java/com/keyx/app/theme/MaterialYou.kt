package com.keyx.app.theme

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Material You: the keyboard takes the wallpaper's colors (Android 12+) and
 * follows the system light/dark setting. Older phones use the fixed
 * `material_you.json`, which is also what the tests check.
 */
object MaterialYou {
    const val ID = "material_you"

    fun live(context: Context, fallback: KeyboardTheme): KeyboardTheme =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { build(context) }.getOrDefault(fallback) else fallback

    @RequiresApi(Build.VERSION_CODES.S)
    private fun build(context: Context): KeyboardTheme {
        fun c(id: Int) = context.getColor(id)
        fun alpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a shl 24)
        val r = android.R.color::class.java
        fun sys(name: String) = c(r.getField("system_$name").getInt(null))
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return if (dark) KeyboardTheme(
            id = ID, name = "Material You",
            keyboardBackground = sys("neutral1_900"),
            stripBackground = sys("neutral1_900"),
            keyFace = sys("neutral2_800"),
            keyBorder = sys("neutral2_700"),
            keyPressed = sys("accent1_700"),
            keyText = sys("neutral1_50"),
            hintText = alpha(sys("neutral2_300"), 0xAA),
            functionText = sys("accent1_200"),
            candidateText = sys("neutral2_200"),
            candidateHighlight = sys("accent1_100"),
            divider = sys("neutral2_700"),
            popupBackground = sys("neutral2_700"),
            popupBorder = sys("accent1_200"),
            gestureTrail = alpha(sys("accent1_200"), 0x99),
            accent = sys("accent1_200"),
        ) else KeyboardTheme(
            id = ID, name = "Material You",
            keyboardBackground = sys("neutral2_100"),
            stripBackground = sys("neutral2_100"),
            keyFace = sys("neutral1_10"),
            keyBorder = sys("neutral2_200"),
            keyPressed = sys("accent1_100"),
            keyText = sys("neutral1_900"),
            hintText = alpha(sys("neutral2_500"), 0xCC),
            functionText = sys("accent1_600"),
            candidateText = sys("neutral2_600"),
            candidateHighlight = sys("accent1_700"),
            divider = sys("neutral2_200"),
            popupBackground = sys("neutral1_10"),
            popupBorder = sys("accent1_600"),
            gestureTrail = alpha(sys("accent1_600"), 0x99),
            accent = sys("accent1_600"),
            appBackground = sys("neutral1_50"),
        )
    }
}
