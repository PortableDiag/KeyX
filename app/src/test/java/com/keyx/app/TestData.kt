package com.keyx.app

import com.keyx.app.layout.LanguageLayout
import com.keyx.app.predict.Dictionary
import com.keyx.app.predict.EmojiPredictor
import com.keyx.app.predict.KeyGrid
import com.keyx.app.predict.LearnedModel
import com.keyx.app.predict.Suggester
import java.io.File

/** The real bundled assets — tests run against what ships, not a toy list. */
object TestData {
    private val assets = File("src/main/assets")

    fun text(path: String): String = File(assets, path).readText()

    private val dicts = HashMap<String, Dictionary>()

    fun dictionary(lang: String): Dictionary =
        dicts.getOrPut(lang) { File(assets, "dicts/$lang.tsv").bufferedReader().use { Dictionary.parse(it) } }

    fun layout(id: String): LanguageLayout = LanguageLayout.parse(text("layouts/$id.json"))

    val emoji: EmojiPredictor by lazy { File(assets, "emoji/predict.tsv").bufferedReader().use { EmojiPredictor.parse(it) } }

    fun suggester(layoutId: String = "en_US", learned: LearnedModel = LearnedModel()): Suggester {
        val layout = layout(layoutId)
        return Suggester(layout.learning, dictionary(layout.dictionary), learned, KeyGrid(layout.rows), emoji)
    }
}
