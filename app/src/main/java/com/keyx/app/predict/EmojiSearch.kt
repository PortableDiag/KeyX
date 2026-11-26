package com.keyx.app.predict

/**
 * Finds emoji by name and by the words that predict them: "pizz" -> 🍕, "lol" -> 😂,
 * "red heart" -> ❤️. Every word of the query has to start a word of the emoji's
 * name or keywords; whole-word and name-leading matches rank first, then Unicode order.
 */
class EmojiSearch(emoji: List<Pair<String, String>>, keywords: Map<String, String> = emptyMap()) {
    private class Entry(val emoji: String, val order: Int, val name: List<String>, val keys: Set<String>)

    private val entries: List<Entry>

    init {
        val byEmoji = HashMap<String, MutableSet<String>>()
        for ((word, e) in keywords) byEmoji.getOrPut(base(e)) { HashSet() } += word.lowercase()
        val seen = HashSet<String>()
        entries = emoji.mapIndexedNotNull { i, (e, name) ->
            if (!seen.add(e)) return@mapIndexedNotNull null
            Entry(e, i, words(name), byEmoji[base(e)].orEmpty())
        }
    }

    fun search(query: String, limit: Int = 40): List<String> {
        val q = words(query)
        if (q.isEmpty()) return emptyList()
        return entries.mapNotNull { en -> score(en, q)?.let { en to it } }
            .sortedWith(compareByDescending<Pair<Entry, Int>> { it.second }.thenBy { it.first.order })
            .take(limit)
            .map { it.first.emoji }
    }

    /** Null when a query word starts no word of the entry. */
    private fun score(en: Entry, q: List<String>): Int? {
        var total = 0
        for ((qi, w) in q.withIndex()) {
            val isLast = qi == q.size - 1
            var best = -1
            if (w in en.keys) best = 6
            en.name.forEachIndexed { i, n ->
                val s = when {
                    n == w -> if (i == 0) 8 else 5
                    // Only the word being typed may be unfinished: "red hea" finds ❤️, "re heart" does not.
                    isLast && n.startsWith(w) -> if (i == 0) 4 else 2
                    else -> -1
                }
                if (s > best) best = s
            }
            if (best < 0 && isLast) {
                if (en.keys.any { it.startsWith(w) }) best = 3
            }
            if (best < 0) return null
            total += best
        }
        // A short name is a closer match than a long one with the same words in it.
        return total * 10 - en.name.size
    }

    private companion object {
        fun words(s: String): List<String> =
            s.lowercase().split(' ', '-', ':', ',', '.', '’', '\'', '(', ')', '&', '!', '“', '”')
                .filter { it.isNotEmpty() }

        /** The emoji without its variation selector, so "❤" from a keyword list finds "❤️". */
        fun base(e: String) = e.replace("️", "")
    }
}
