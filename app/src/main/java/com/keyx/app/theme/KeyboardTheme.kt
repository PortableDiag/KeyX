package com.keyx.app.theme

import org.json.JSONObject

/**
 * A keyboard theme is a JSON object of colors (assets/themes/<id>.json), so a
 * new one is a file, not a build. Colors are `#RRGGBB` or `#AARRGGBB`, parsed
 * here rather than by android.graphics.Color so the parser runs in unit tests.
 */
data class KeyboardTheme(
    val id: String,
    val name: String,
    val keyboardBackground: Int,
    val stripBackground: Int,
    val keyFace: Int,
    val keyBorder: Int,
    val keyPressed: Int,
    val keyText: Int,
    val hintText: Int,
    val functionText: Int,
    val candidateText: Int,
    val candidateHighlight: Int,
    val divider: Int,
    val popupBackground: Int,
    val popupBorder: Int,
    val gestureTrail: Int,
    val accent: Int,
    /** The settings app's background; the keyboard background unless a light theme needs paler paper. */
    val appBackground: Int = keyboardBackground,
) {
    companion object {
        const val DEFAULT_ID = "phosphor_green"

        val FIELDS = listOf(
            "keyboardBackground", "stripBackground", "keyFace", "keyBorder", "keyPressed",
            "keyText", "hintText", "functionText", "candidateText", "candidateHighlight",
            "divider", "popupBackground", "popupBorder", "gestureTrail", "accent",
        )

        fun parse(text: String): KeyboardTheme {
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw ThemeException("Not valid JSON: ${e.message}")
            }
            fun c(name: String): Int {
                val v = json.optString(name)
                if (v.isBlank()) throw ThemeException("\"$name\" is missing")
                return parseColor(v) ?: throw ThemeException("\"$name\": \"$v\" is not #RRGGBB or #AARRGGBB")
            }
            val id = json.optString("id").ifBlank { throw ThemeException("\"id\" is missing") }
            return KeyboardTheme(
                id = id,
                name = json.optString("name").ifBlank { id },
                keyboardBackground = c("keyboardBackground"),
                stripBackground = c("stripBackground"),
                keyFace = c("keyFace"),
                keyBorder = c("keyBorder"),
                keyPressed = c("keyPressed"),
                keyText = c("keyText"),
                hintText = c("hintText"),
                functionText = c("functionText"),
                candidateText = c("candidateText"),
                candidateHighlight = c("candidateHighlight"),
                divider = c("divider"),
                popupBackground = c("popupBackground"),
                popupBorder = c("popupBorder"),
                gestureTrail = c("gestureTrail"),
                accent = c("accent"),
                appBackground = if (json.has("appBackground")) c("appBackground") else c("keyboardBackground"),
            )
        }

        fun parseColor(s: String): Int? {
            val hex = s.trim().removePrefix("#")
            val v = hex.toLongOrNull(16) ?: return null
            return when (hex.length) {
                6 -> (0xFF000000 or v).toInt()
                8 -> v.toInt()
                else -> null
            }
        }
    }
}

class ThemeException(message: String) : Exception(message)
