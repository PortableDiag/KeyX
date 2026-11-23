package com.keyx.app.predict

import java.io.BufferedReader

/** Which words follow which, from the bundled pack: context before the operator has taught any. */
class WordPairs private constructor(private val next: Map<String, Map<String, Float>>) {
    /** ln of how much likelier [word] is after [previous] than anywhere; 0 when the pack doesn't say. */
    fun lift(previous: String, word: String): Double =
        next[previous.lowercase()]?.get(word.lowercase())?.toDouble() ?: 0.0

    /** Words that follow [previous], most frequent first. */
    fun next(previous: String, limit: Int): List<String> =
        next[previous.lowercase()]?.keys?.take(limit).orEmpty()

    companion object {
        val EMPTY = WordPairs(emptyMap())

        fun parse(reader: BufferedReader): WordPairs {
            val map = HashMap<String, Map<String, Float>>()
            reader.forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@forEachLine
                val words = LinkedHashMap<String, Float>()
                for (item in line.substring(tab + 1).split(' ')) {
                    val colon = item.lastIndexOf(':')
                    if (colon > 0) words[item.substring(0, colon)] = item.substring(colon + 1).toFloat() / 10f
                }
                map[line.substring(0, tab)] = words
            }
            return WordPairs(map)
        }
    }
}
