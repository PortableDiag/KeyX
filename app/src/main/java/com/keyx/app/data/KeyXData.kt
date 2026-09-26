package com.keyx.app.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.keyx.app.layout.LanguageLayout
import com.keyx.app.layout.SymbolSet
import com.keyx.app.predict.Dictionary
import com.keyx.app.predict.EmojiPredictor
import com.keyx.app.predict.LearnedModel
import com.keyx.app.theme.KeyboardTheme
import java.io.File
import java.util.concurrent.Executors

/**
 * Everything the keyboard and the settings screen share, one per process: the
 * learned model, settings, layouts, themes and dictionary packs. The keyboard
 * service and the settings activity live in the same process, so a reset in
 * settings clears the very model the keyboard is predicting from.
 */
class KeyXData private constructor(private val context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("keyx", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val store = LearningStore(context.filesDir)

    val learned: LearnedModel = store.load()

    /** Saved settings, minus any language whose layout no longer ships (en_IN did, once). */
    fun settings(): Settings {
        val s = Settings.read(prefs)
        val langs = s.languages.filter { it in bundledLanguages }.ifEmpty { listOf(Settings().currentLanguage) }
        return s.copy(languages = langs, currentLanguage = s.currentLanguage.takeIf { it in langs } ?: langs.first())
    }

    // ---- layouts ----------------------------------------------------------

    val bundledLanguages: List<String> by lazy {
        context.assets.list("layouts").orEmpty().map { it.removeSuffix(".json") }
            .filter { it != "symbols" }.sorted()
    }

    val symbols: SymbolSet by lazy { SymbolSet.parse(asset("layouts/symbols.json")) }

    private fun layoutOverride(id: String) = File(context.filesDir, "layouts/$id.json")

    fun bundledLayoutText(id: String): String = asset("layouts/$id.json")

    fun layoutText(id: String): String =
        layoutOverride(id).takeIf { it.exists() }?.readText() ?: bundledLayoutText(id)

    fun isLayoutEdited(id: String): Boolean = layoutOverride(id).exists()

    /** The operator's edited layout when it parses, the bundled one otherwise. */
    fun layout(id: String): LanguageLayout {
        val edited = layoutOverride(id)
        if (edited.exists()) runCatching { return LanguageLayout.parse(edited.readText()) }
        return LanguageLayout.parse(bundledLayoutText(id))
    }

    /** Validates before saving, so a broken edit never reaches the keyboard. */
    fun saveLayout(id: String, text: String): LanguageLayout {
        val parsed = LanguageLayout.parse(text)
        if (parsed.id != id) throw com.keyx.app.layout.LayoutException("\"id\" must stay \"$id\"")
        dictionaryAsset(parsed.dictionary)
        layoutOverride(id).apply { parentFile?.mkdirs() }.writeText(text)
        touch()
        return parsed
    }

    fun resetLayout(id: String) {
        layoutOverride(id).delete()
        touch()
    }

    // ---- themes -----------------------------------------------------------

    private val customTheme = File(context.filesDir, "themes/custom.json")

    fun themeIds(): List<String> {
        val bundled = context.assets.list("themes").orEmpty().map { it.removeSuffix(".json") }.sorted()
        return if (customTheme.exists()) bundled + CUSTOM_THEME else bundled
    }

    fun themeText(id: String): String =
        if (id == CUSTOM_THEME && customTheme.exists()) customTheme.readText() else asset("themes/$id.json")

    fun theme(id: String): KeyboardTheme =
        runCatching { KeyboardTheme.parse(themeText(id)) }.getOrNull()
            ?: KeyboardTheme.parse(asset("themes/${KeyboardTheme.DEFAULT_ID}.json"))

    fun saveCustomTheme(text: String): KeyboardTheme {
        val parsed = KeyboardTheme.parse(text)
        if (parsed.id != CUSTOM_THEME) throw com.keyx.app.theme.ThemeException("\"id\" must be \"$CUSTOM_THEME\"")
        customTheme.apply { parentFile?.mkdirs() }.writeText(text)
        touch()
        return parsed
    }

    // ---- dictionaries and emoji -------------------------------------------

    private val dictionaries = HashMap<String, Dictionary>()

    private fun dictionaryAsset(name: String) {
        if (context.assets.list("dicts").orEmpty().none { it == "$name.tsv" }) {
            throw com.keyx.app.layout.LayoutException("no dictionary pack \"$name\"")
        }
    }

    /** Loads off the main thread; [done] runs on the main thread. */
    fun dictionary(name: String, done: (Dictionary?) -> Unit) {
        dictionaries[name]?.let { done(it); return }
        io.execute {
            val d = runCatching {
                context.assets.open("dicts/$name.tsv").bufferedReader().use { Dictionary.parse(it) }
            }.getOrNull()
            main.post {
                if (d != null) {
                    // Keep one pack resident: a second would double the memory for a language not in use.
                    dictionaries.keys.retainAll { it == name }
                    dictionaries[name] = d
                }
                done(d)
            }
        }
    }

    val emojiPredictor: EmojiPredictor by lazy {
        context.assets.open("emoji/predict.tsv").bufferedReader().use { EmojiPredictor.parse(it) }
    }

    /** (group, emoji) in Unicode order. */
    val emoji: List<Pair<String, String>> by lazy {
        context.assets.open("emoji/emoji.tsv").bufferedReader().useLines { lines ->
            lines.mapNotNull { l -> l.split('\t').takeIf { it.size >= 2 }?.let { it[0] to it[1] } }.toList()
        }
    }

    fun emojiRecents(): List<String> =
        prefs.getString(EMOJI_RECENTS, "").orEmpty().split(' ').filter { it.isNotBlank() }

    fun useEmoji(e: String) {
        val list = (listOf(e) + emojiRecents().filter { it != e }).take(32)
        prefs.edit().putString(EMOJI_RECENTS, list.joinToString(" ")).apply()
    }

    // ---- learning ---------------------------------------------------------

    private val saveTask = Runnable { saveNow() }

    fun scheduleSave() {
        main.removeCallbacks(saveTask)
        main.postDelayed(saveTask, SAVE_DELAY_MS)
    }

    fun saveNow() {
        main.removeCallbacks(saveTask)
        val json = learned.toJson().toString()
        io.execute { runCatching { store.save(json) } }
    }

    /**
     * Wipes everything KeyX has learned: the model in memory, the sealed file,
     * the Keystore key that sealed it, and the emoji recents. Nothing survives.
     */
    fun resetLearning() {
        main.removeCallbacks(saveTask)
        learned.clear()
        prefs.edit().remove(EMOJI_RECENTS).apply()
        io.execute { store.wipe() }
        resetListeners.forEach { it() }
    }

    fun importWords(language: String, text: String): Int {
        val n = learned.importWords(language, text)
        saveNow()
        return n
    }

    private val resetListeners = ArrayList<() -> Unit>()
    fun onReset(listener: () -> Unit) { resetListeners += listener }
    fun removeOnReset(listener: () -> Unit) { resetListeners -= listener }

    /** Bumps a pref so a running keyboard rebuilds after a layout or theme file changed. */
    private fun touch() = prefs.edit().putLong(FILES_CHANGED, System.currentTimeMillis()).apply()

    private fun asset(path: String): String = context.assets.open(path).bufferedReader().use { it.readText() }

    companion object {
        const val CUSTOM_THEME = "custom"
        private const val EMOJI_RECENTS = "emoji_recents"
        private const val FILES_CHANGED = "files_changed"
        private const val SAVE_DELAY_MS = 3000L

        // Holds the application context only (see get), which lives as long as the process.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: KeyXData? = null

        fun get(context: Context): KeyXData =
            instance ?: synchronized(this) {
                instance ?: KeyXData(context.applicationContext).also { instance = it }
            }
    }
}
