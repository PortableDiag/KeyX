package com.keyx.app.predict

import java.text.Normalizer
import kotlin.math.ln
import kotlin.math.min

data class Suggestion(val text: String, val kind: Kind) {
    enum class Kind { TYPED, CORRECTION, COMPLETION, PREDICTION, EMOJI }
}

/**
 * The candidate bar, SwiftKey's shape: the center is what the space bar will
 * commit, the sides are the next-best alternatives. When autocorrect is going to
 * fire, the left slot holds the word exactly as typed, so it is one tap to keep.
 */
data class Strip(
    val left: Suggestion? = null,
    val center: Suggestion? = null,
    val right: Suggestion? = null,
    /** Set when the space bar will replace the typed word with this one. */
    val autocorrect: String? = null,
) {
    val all: List<Suggestion> get() = listOfNotNull(left, center, right)

    companion object {
        val EMPTY = Strip()
    }
}

class Suggester(
    val language: String,
    private val dictionary: Dictionary,
    private val learned: LearnedModel,
    private val grid: KeyGrid,
    private val emoji: EmojiPredictor? = null,
) {
    /** A word the operator has used this often is theirs, and is never "corrected". */
    fun isKnown(word: String): Boolean =
        !learned.isBlocked(language, word) &&
            (dictionary.contains(word) || learned.count(language, word) >= KNOWN_AFTER)

    private data class Query(
        val typed: String,
        val previous: String?,
        val autocorrect: Boolean,
        val emojiOn: Boolean,
        val learnedVersion: Long,
    )
    private var lastQuery: Query? = null
    private var lastStrip = Strip.EMPTY

    /**
     * Runs on the main thread on every keystroke, and again when space asks for
     * the correction; the second ask is answered from the first.
     */
    fun forWord(typed: String, previous: String?, autocorrect: Boolean, emojiOn: Boolean): Strip {
        val q = Query(typed, previous, autocorrect, emojiOn, learned.version)
        if (q == lastQuery) return lastStrip
        return compute(typed, previous, autocorrect, emojiOn).also { lastQuery = q; lastStrip = it }
    }

    private fun compute(typed: String, previous: String?, autocorrect: Boolean, emojiOn: Boolean): Strip {
        if (typed.isEmpty()) return predictNext(previous, emojiOn)
        val lower = typed.lowercase()
        val known = isKnown(typed)
        val ranked = HashMap<String, Pair<String, Double>>() // lower -> (form, score)
        fun offer(form: String, score: Double) {
            if (learned.isBlocked(language, form)) return
            val key = form.lowercase()
            val cur = ranked[key]
            if (cur == null || cur.second < score) ranked[key] = form to score
        }

        for (e in dictionary.completions(lower, 12)) {
            if (e.lower != lower) offer(e.word, prior(e.word, previous) - COMPLETION_PENALTY)
        }
        for ((w, _) in learned.completions(language, lower, 6)) {
            if (!w.equals(typed, true)) offer(w, prior(w, previous) - COMPLETION_PENALTY)
        }
        val fuzzy = corrections(lower, previous).filterNot { learned.isBlocked(language, it.first) }
        for ((form, s) in fuzzy) offer(form, s)
        ranked.remove(lower)

        val best = fuzzy.firstOrNull()
        val correction = when {
            !autocorrect -> null
            known -> caseFix(typed)
            !canCorrect(typed) -> null
            best != null -> matchCase(typed, best.first)
            else -> null
        }
        val others = ranked.values.sortedByDescending { it.second }
            .map { matchCase(typed, it.first) }
            .filter { it != correction && !it.equals(typed, true) }

        val center = if (correction != null) {
            Suggestion(correction, Suggestion.Kind.CORRECTION)
        } else {
            Suggestion(typed, Suggestion.Kind.TYPED)
        }
        val left = if (correction != null) {
            Suggestion(typed, Suggestion.Kind.TYPED)
        } else {
            others.getOrNull(0)?.let { Suggestion(it, Suggestion.Kind.COMPLETION) }
        }
        val rightWord = others.getOrNull(if (correction != null) 0 else 1)
        val emojiFor = if (emojiOn) emoji?.forWord(correction ?: typed) else null
        val right = when {
            emojiFor != null -> Suggestion(emojiFor, Suggestion.Kind.EMOJI)
            rightWord != null -> Suggestion(rightWord, Suggestion.Kind.COMPLETION)
            else -> null
        }
        return Strip(left, center, right, correction)
    }

    /** Next-word predictions after a committed word, or the commonest words with no context. */
    fun predictNext(previous: String?, emojiOn: Boolean): Strip {
        val words = ArrayList<String>()
        if (previous != null) words += learned.next(language, previous, 3)
        for (e in dictionary.top(8)) {
            if (words.size >= 3) break
            if (words.none { it.equals(e.word, true) } && !learned.isBlocked(language, e.word)) words += e.word
        }
        val emojiFor = if (emojiOn && previous != null) emoji?.forWord(previous) else null
        fun p(i: Int) = words.getOrNull(i)?.let { Suggestion(it, Suggestion.Kind.PREDICTION) }
        return Strip(
            left = p(1),
            center = p(0),
            right = emojiFor?.let { Suggestion(it, Suggestion.Kind.EMOJI) } ?: p(2),
        )
    }

    /** Fuzzy matches for a typed word, best first, each as (form, score). */
    fun corrections(lower: String, previous: String?): List<Pair<String, Double>> {
        if (lower.length < 2 || lower.any { it.isDigit() }) return emptyList()
        val budget = when {
            lower.length <= 3 -> 1.0
            lower.length <= 6 -> 1.6
            else -> 2.2
        }
        // The first letter is almost never the slip: only words starting with it, a
        // key next to it, or the second letter (a swapped first pair) are scored.
        val first = base(lower[0])
        val firsts = HashSet<Char>()
        firsts += first
        firsts += base(lower[1])
        for (c in grid.letters) if (grid.adjacent(c, first)) firsts += base(c)
        val out = ArrayList<Pair<String, Double>>()
        for (e in dictionary.candidates(firsts, lower.length, 2)) {
            if (e.lower == lower) continue
            val cost = editCost(lower, e.lower, budget)
            if (cost <= budget) out += e.word to (prior(e.word, previous) - EDIT_WEIGHT * cost)
        }
        for ((w, _) in learned.all(language)) {
            val l = w.lowercase()
            if (l == lower || kotlin.math.abs(l.length - lower.length) > 2 || base(l[0]) !in firsts) continue
            if (learned.count(language, w) < KNOWN_AFTER) continue
            val cost = editCost(lower, l, budget)
            if (cost <= budget) out += w to (prior(w, previous) - EDIT_WEIGHT * cost)
        }
        // One entry per word, in the form that scored best ("leben" vs "Leben").
        return out.sortedByDescending { it.second }.distinctBy { it.first.lowercase() }.take(8)
    }

    /** log-scale likelihood of [word] here: how common it is, how often the operator uses it, and after what. */
    fun prior(word: String, previous: String?): Double {
        val base = ln(1.0 + dictionary.forms(word).firstOrNull { it.word == word }?.count
            .let { it ?: dictionary.count(word) })
        val mine = learned.count(language, word)
        val pair = if (previous != null) learned.pair(language, previous, word) else 0
        return base + LEARNED_WEIGHT * ln(1.0 + mine) + PAIR_WEIGHT * ln(1.0 + pair)
    }

    /**
     * Weighted Damerau-Levenshtein: a slip onto a neighboring key, a doubled
     * letter, a missing apostrophe or accent all cost less than a random edit.
     * Returns early with [bound] + 1 once no alignment can come in under [bound].
     */
    fun editCost(typed: String, word: String, bound: Double): Double {
        val n = typed.length
        val m = word.length
        var prev2 = DoubleArray(m + 1)
        var prev = DoubleArray(m + 1)
        for (j in 1..m) prev[j] = prev[j - 1] + insCost(word, j - 1)
        var cur = DoubleArray(m + 1)
        for (i in 1..n) {
            cur[0] = prev[0] + delCost(typed, i - 1)
            var rowMin = cur[0]
            for (j in 1..m) {
                val a = typed[i - 1]
                val b = word[j - 1]
                var best = prev[j - 1] + subCost(a, b)
                best = min(best, prev[j] + delCost(typed, i - 1))
                best = min(best, cur[j - 1] + insCost(word, j - 1))
                if (i > 1 && j > 1 && a == word[j - 2] && typed[i - 2] == b && a != b) {
                    best = min(best, prev2[j - 2] + TRANSPOSE)
                }
                cur[j] = best
                if (best < rowMin) rowMin = best
            }
            if (rowMin > bound) return bound + 1
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[m]
    }

    private fun subCost(a: Char, b: Char): Double = when {
        a == b -> 0.0
        base(a) == base(b) -> ACCENT
        grid.adjacent(a, b) -> NEIGHBOR
        else -> 1.0
    }

    /** The word has a character the typed text lacks. */
    private fun insCost(word: String, j: Int): Double {
        val c = word[j]
        return when {
            c == '\'' || c == '-' -> APOSTROPHE
            j > 0 && word[j - 1] == c -> DOUBLED
            else -> 1.0
        }
    }

    /** The typed text has a character the word lacks. */
    private fun delCost(typed: String, i: Int): Double =
        if (i > 0 && typed[i - 1] == typed[i]) DOUBLED else 1.0

    /**
     * "i" -> "I", "john" -> "John": the word is right but only exists capitalized.
     * Never applied when the operator has typed it in lowercase themselves.
     */
    private fun caseFix(typed: String): String? {
        if (typed.any { it.isUpperCase() } || !typed.all { it.isLetter() || it == '\'' }) return null
        val forms = dictionary.forms(typed).filterNot { learned.isBlocked(language, it.word) }
        if (forms.isEmpty() || forms.any { it.word == typed }) return null
        if (learned.forms(language, typed).any { it == typed }) return null
        return forms.first().word
    }

    private fun canCorrect(typed: String): Boolean =
        typed.length >= 2 && typed.all { it.isLetter() || it == '\'' } &&
            // All-caps is deliberate (an acronym), and so is mixed case after the first letter.
            !(typed.length > 1 && typed.drop(1).any { it.isUpperCase() })

    companion object {
        const val KNOWN_AFTER = 2
        private const val EDIT_WEIGHT = 4.5
        private const val COMPLETION_PENALTY = 1.5
        private const val LEARNED_WEIGHT = 2.5
        private const val PAIR_WEIGHT = 2.0
        private const val NEIGHBOR = 0.6
        private const val ACCENT = 0.2
        private const val APOSTROPHE = 0.15
        // A missed double letter ("helo") is the commonest slip there is.
        private const val DOUBLED = 0.3
        private const val TRANSPOSE = 0.7

        /** Latin and Cyrillic, precomputed: this is called in the inner loop of [editCost]. */
        private val BASE = CharArray(0x530) { i ->
            Normalizer.normalize(i.toChar().toString(), Normalizer.Form.NFD)[0].lowercaseChar()
        }

        fun base(c: Char): Char =
            if (c.code < BASE.size) BASE[c.code]
            else Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0].lowercaseChar()

        /**
         * The case the operator meant: a proper noun or German noun keeps its
         * capital; a capitalized or all-caps typed word carries its case over.
         */
        fun matchCase(typed: String, form: String): String {
            if (form.isEmpty() || typed.isEmpty()) return form
            val allCaps = typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() }
            return when {
                allCaps -> form.uppercase()
                typed[0].isUpperCase() -> form.replaceFirstChar { it.uppercaseChar() }
                else -> form
            }
        }
    }
}
