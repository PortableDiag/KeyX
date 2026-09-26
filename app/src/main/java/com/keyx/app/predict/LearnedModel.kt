package com.keyx.app.predict

import org.json.JSONObject

/**
 * What KeyX has learned from the operator's own typing, per dictionary language:
 * word counts and word-pair counts. It is the whole of the learned state — reset
 * replaces it with an empty one, and there is nothing else to wipe.
 *
 * Words keep the case they were typed in ("KeyX" stays "KeyX") but are indexed
 * by their lowercase form, so every lookup the suggester makes per keystroke is
 * a hash lookup, not a scan.
 */
class LearnedModel {
    /** language -> lowercase word -> typed form -> count */
    private val words = HashMap<String, HashMap<String, HashMap<String, Int>>>()

    /** language -> lowercase previous word -> lowercase word -> count */
    private val pairs = HashMap<String, HashMap<String, HashMap<String, Int>>>()

    val isEmpty: Boolean get() = words.values.all { it.isEmpty() }

    /** Bumped on every change, so anything cached from the model knows it is stale. */
    var version = 0L
        private set

    fun wordCount(language: String): Int = words[language]?.values?.sumOf { it.size } ?: 0

    fun pairCount(language: String): Int = pairs[language]?.values?.sumOf { it.size } ?: 0

    fun learn(language: String, previous: String?, word: String, weight: Int = 1) {
        if (!isLearnable(word)) return
        version++
        val lower = word.lowercase()
        val forms = words.getOrPut(language) { HashMap() }.getOrPut(lower) { HashMap() }
        forms[word] = (forms[word] ?: 0) + weight
        if (previous != null && isLearnable(previous)) {
            val next = pairs.getOrPut(language) { HashMap() }.getOrPut(previous.lowercase()) { HashMap() }
            next[lower] = (next[lower] ?: 0) + weight
        }
        prune(language)
    }

    /** Count of [word] as typed, or of all its cases when that exact form was never typed. */
    fun count(language: String, word: String): Int {
        val forms = words[language]?.get(word.lowercase()) ?: return 0
        return forms[word] ?: forms.values.sum()
    }

    /** The typed forms of [word], most used first. */
    fun forms(language: String, word: String): List<String> =
        words[language]?.get(word.lowercase()).orEmpty().entries.sortedByDescending { it.value }.map { it.key }

    fun pair(language: String, previous: String, word: String): Int =
        pairs[language]?.get(previous.lowercase())?.get(word.lowercase()) ?: 0

    /** Words that followed [previous], most frequent first, each in its most used form. */
    fun next(language: String, previous: String, limit: Int): List<String> =
        pairs[language]?.get(previous.lowercase()).orEmpty().entries
            .sortedByDescending { it.value }.take(limit).map { best(language, it.key) }

    /** Learned words starting with [prefix], most used first, as (form, total count). */
    fun completions(language: String, prefix: String, limit: Int): List<Pair<String, Int>> {
        val p = prefix.lowercase()
        return words[language].orEmpty().entries.asSequence()
            .filter { it.key.startsWith(p) }
            .map { (_, forms) -> forms.maxBy { it.value }.key to forms.values.sum() }
            .sortedByDescending { it.second }.take(limit).toList()
    }

    /** Every learned word as (most used form, total count). */
    fun all(language: String): Sequence<Pair<String, Int>> =
        words[language].orEmpty().values.asSequence().map { forms -> forms.maxBy { it.value }.key to forms.values.sum() }

    private fun best(language: String, lower: String): String =
        words[language]?.get(lower)?.maxByOrNull { it.value }?.key ?: lower

    fun forget(language: String, word: String) {
        version++
        val lower = word.lowercase()
        words[language]?.remove(lower)
        pairs[language]?.values?.forEach { it.remove(lower) }
        pairs[language]?.remove(lower)
    }

    fun clear() {
        version++
        words.clear()
        pairs.clear()
    }

    /**
     * A plain word list: one word per line, optionally followed by a tab, space
     * or comma and a count. Blank lines and `#` comments are skipped. Returns the
     * number of words taken.
     */
    fun importWords(language: String, text: String): Int {
        var taken = 0
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split('\t', ',', ' ').filter { it.isNotBlank() }
            val word = parts.first()
            val given = parts.getOrNull(1)?.toIntOrNull()
            // "word" or "word count"; anything longer is a phrase, not a word list line.
            if (parts.size > 2 || (parts.size == 2 && given == null)) continue
            val count = given?.coerceIn(1, 1000) ?: IMPORTED_WEIGHT
            if (!isLearnable(word)) continue
            learn(language, null, word, count)
            taken++
        }
        return taken
    }

    /** The inverse of [importWords]: `word<TAB>count`, most used first. */
    fun exportWords(language: String): String =
        words[language].orEmpty().values.flatMap { it.entries }.sortedByDescending { it.value }
            .joinToString("") { "${it.key}\t${it.value}\n" }

    fun toJson(): JSONObject {
        val root = JSONObject().put("version", 1)
        val langs = JSONObject()
        for (lang in words.keys + pairs.keys) {
            val u = JSONObject()
            words[lang]?.values?.forEach { forms -> forms.forEach { (w, c) -> u.put(w, c) } }
            val b = JSONObject()
            pairs[lang]?.forEach { (prev, next) ->
                val n = JSONObject()
                next.forEach { (w, c) -> n.put(w, c) }
                b.put(prev, n)
            }
            langs.put(lang, JSONObject().put("words", u).put("pairs", b))
        }
        return root.put("languages", langs)
    }

    private fun prune(language: String) {
        val p = pairs[language] ?: return
        if (p.values.sumOf { it.size } > MAX_PAIRS) {
            // Drop the pairs seen only once; they are the noise, and the rest is what
            // makes a prediction.
            p.values.forEach { m -> m.values.removeAll { it <= 1 } }
            p.values.removeAll { it.isEmpty() }
        }
        val w = words[language] ?: return
        if (w.size > MAX_WORDS) {
            w.values.forEach { forms -> forms.values.removeAll { it <= 1 } }
            w.values.removeAll { it.isEmpty() }
        }
    }

    companion object {
        const val IMPORTED_WEIGHT = 5
        private const val MAX_PAIRS = 60_000
        private const val MAX_WORDS = 40_000

        fun isLearnable(word: String): Boolean =
            word.length in 1..40 && word.any { it.isLetter() } &&
                word.all { it.isLetterOrDigit() || it == '\'' || it == '-' }

        fun fromJson(json: JSONObject): LearnedModel {
            val model = LearnedModel()
            val langs = json.optJSONObject("languages") ?: return model
            for (lang in langs.keys()) {
                val l = langs.getJSONObject(lang)
                val u = l.optJSONObject("words")
                if (u != null) {
                    val m = model.words.getOrPut(lang) { HashMap() }
                    for (w in u.keys()) m.getOrPut(w.lowercase()) { HashMap() }[w] = u.getInt(w)
                }
                val b = l.optJSONObject("pairs")
                if (b != null) {
                    val m = model.pairs.getOrPut(lang) { HashMap() }
                    for (prev in b.keys()) {
                        val n = b.getJSONObject(prev)
                        val next = m.getOrPut(prev.lowercase()) { HashMap() }
                        for (w in n.keys()) next[w.lowercase()] = (next[w.lowercase()] ?: 0) + n.getInt(w)
                    }
                }
            }
            return model
        }
    }
}
