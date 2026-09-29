package com.keyx.app.data

import android.content.SharedPreferences
import com.keyx.app.ime.EngineOptions
import com.keyx.app.layout.BuildOptions
import com.keyx.app.theme.KeyboardTheme

/**
 * Every switch in KeyX. The defaults are the operator's SwiftKey configuration as
 * measured on the phone: every feature in the parity list on, vibration weak.
 */
data class Settings(
    val languages: List<String> = listOf("en_US"),
    val currentLanguage: String = "en_US",
    val theme: String = KeyboardTheme.DEFAULT_ID,
    val numberRow: Boolean = true,
    val autocorrect: Boolean = true,
    val predictions: Boolean = true,
    val emojiPredictions: Boolean = true,
    val emojiKey: Boolean = true,
    val swipe: Boolean = true,
    val cursorControl: Boolean = true,
    val arrowRow: Boolean = true,
    val quickDelete: Boolean = true,
    val longPressSymbols: Boolean = true,
    val allAccents: Boolean = true,
    val keyPopup: Boolean = true,
    val voice: Boolean = true,
    val clipboardStrip: Boolean = true,
    val autoCaps: Boolean = true,
    val doubleSpacePeriod: Boolean = true,
    val spaceAfterPunctuation: Boolean = true,
    val vibrate: Boolean = true,
    val vibrateMs: Int = 10,
    val heightPercent: Int = 100,
) {
    val engineOptions: EngineOptions
        get() = EngineOptions(autocorrect, predictions, emojiPredictions, autoCaps, doubleSpacePeriod, spaceAfterPunctuation)

    val buildOptions: BuildOptions
        get() = BuildOptions(numberRow, longPressSymbols, allAccents, emojiKey, voice)

    companion object {
        const val LANGUAGES = "languages"
        const val CURRENT_LANGUAGE = "current_language"
        const val THEME = "theme"
        const val VIBRATE_MS = "vibrate_ms"
        const val HEIGHT = "height_percent"

        /** Boolean switches: pref key to getter, in the order the settings screen lists them. */
        val SWITCHES: List<Pair<String, (Settings) -> Boolean>> = listOf(
            "autocorrect" to { s: Settings -> s.autocorrect },
            "predictions" to { s: Settings -> s.predictions },
            "emoji_predictions" to { s: Settings -> s.emojiPredictions },
            "swipe" to { s: Settings -> s.swipe },
            "auto_caps" to { s: Settings -> s.autoCaps },
            "double_space_period" to { s: Settings -> s.doubleSpacePeriod },
            "space_after_punctuation" to { s: Settings -> s.spaceAfterPunctuation },
            "number_row" to { s: Settings -> s.numberRow },
            "arrow_row" to { s: Settings -> s.arrowRow },
            "emoji_key" to { s: Settings -> s.emojiKey },
            "long_press_symbols" to { s: Settings -> s.longPressSymbols },
            "all_accents" to { s: Settings -> s.allAccents },
            "key_popup" to { s: Settings -> s.keyPopup },
            "voice" to { s: Settings -> s.voice },
            "clipboard_strip" to { s: Settings -> s.clipboardStrip },
            "cursor_control" to { s: Settings -> s.cursorControl },
            "quick_delete" to { s: Settings -> s.quickDelete },
            "vibrate" to { s: Settings -> s.vibrate },
        )

        fun read(p: SharedPreferences): Settings {
            val d = Settings()
            fun b(key: String, def: Boolean) = p.getBoolean(key, def)
            val languages = p.getString(LANGUAGES, null)?.split(',')?.filter { it.isNotBlank() }
                ?.ifEmpty { null } ?: d.languages
            val current = p.getString(CURRENT_LANGUAGE, null)?.takeIf { it in languages } ?: languages.first()
            return Settings(
                languages = languages,
                currentLanguage = current,
                theme = p.getString(THEME, null) ?: d.theme,
                numberRow = b("number_row", d.numberRow),
                autocorrect = b("autocorrect", d.autocorrect),
                predictions = b("predictions", d.predictions),
                emojiPredictions = b("emoji_predictions", d.emojiPredictions),
                emojiKey = b("emoji_key", d.emojiKey),
                swipe = b("swipe", d.swipe),
                cursorControl = b("cursor_control", d.cursorControl),
                arrowRow = b("arrow_row", d.arrowRow),
                quickDelete = b("quick_delete", d.quickDelete),
                longPressSymbols = b("long_press_symbols", d.longPressSymbols),
                allAccents = b("all_accents", d.allAccents),
                keyPopup = b("key_popup", d.keyPopup),
                voice = b("voice", d.voice),
                clipboardStrip = b("clipboard_strip", d.clipboardStrip),
                autoCaps = b("auto_caps", d.autoCaps),
                doubleSpacePeriod = b("double_space_period", d.doubleSpacePeriod),
                spaceAfterPunctuation = b("space_after_punctuation", d.spaceAfterPunctuation),
                vibrate = b("vibrate", d.vibrate),
                vibrateMs = p.getInt(VIBRATE_MS, d.vibrateMs),
                heightPercent = p.getInt(HEIGHT, d.heightPercent),
            )
        }
    }
}
