package com.keyx.app.predict

import java.io.BufferedReader

/** English words that suggest an emoji ("pizza" -> 🍕), from assets/emoji/predict.tsv. */
class EmojiPredictor(private val map: Map<String, String>) {
    fun forWord(word: String): String? = map[word.lowercase()]

    companion object {
        fun parse(reader: BufferedReader): EmojiPredictor {
            val map = HashMap<String, String>()
            reader.forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) map[line.substring(0, tab)] = line.substring(tab + 1).trim()
            }
            return EmojiPredictor(map)
        }
    }
}
