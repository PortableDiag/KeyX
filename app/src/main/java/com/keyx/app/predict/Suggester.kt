package com.keyx.app.predict

import java.text.Normalizer
import kotlin.math.ln
import kotlin.math.max
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
    /** The next words after these three, for the second row of suggestions. */
    val extra: List<Suggestion> = emptyList(),
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
    private val pairs: WordPairs = WordPairs.EMPTY,
) {
    /** A word the operator has used this often is theirs, and is never "corrected". */
    fun isKnown(word: String): Boolean =
        !learned.isBlocked(language, word) &&
            (dictionary.contains(word) || learned.count(language, word) >= KNOWN_AFTER)

    /** How many words to find beyond the strip's three: a second row's worth, or the word grid's. */
    var extraCount = EXTRA

    private data class Query(
        val typed: String,
        val previous: String?,
        val autocorrect: Boolean,
        val emojiOn: Boolean,
        val learnedVersion: Long,
        val extraCount: Int,
        val touches: List<Touch?>?,
    )
    private var lastQuery: Query? = null
    private var lastStrip = Strip.EMPTY

    /**
     * Runs on the main thread on every keystroke, and again when space asks for
     * the correction; the second ask is answered from the first.
     */
    fun forWord(
        typed: String,
        previous: String?,
        autocorrect: Boolean,
        emojiOn: Boolean,
        touches: List<Touch?>? = null,
    ): Strip {
        val t = touches?.takeIf { it.size == typed.length && it.any { x -> x != null } }
        val q = Query(typed, previous, autocorrect, emojiOn, learned.version, extraCount, t)
        if (q == lastQuery) return lastStrip
        return compute(typed, previous, autocorrect, emojiOn, t).also { lastQuery = q; lastStrip = it }
    }

    private fun compute(typed: String, previous: String?, autocorrect: Boolean, emojiOn: Boolean, touches: List<Touch?>?): Strip {
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

        for (e in dictionary.completions(lower, max(12, extraCount + 9))) {
            if (e.lower != lower) offer(e.word, prior(e.word, previous) - COMPLETION_PENALTY)
        }
        for ((w, _) in learned.completions(language, lower, max(6, extraCount))) {
            if (!w.equals(typed, true)) offer(w, prior(w, previous) - COMPLETION_PENALTY)
        }
        val fixes = fixes(lower, previous, touches).filterNot { learned.isBlocked(language, it.form) }
        for (f in fixes) offer(f.form, f.score)
        ranked.remove(lower)

        val best = fixes.firstOrNull { it.force }
        val correction = when {
            !autocorrect -> null
            known -> apostropheFix(typed) ?: shadowFix(typed, previous, fixes) ?: caseFix(typed)
            !canCorrect(typed) -> null
            best != null -> matchCase(typed, best.form)
            else -> null
        }
        val others = ranked.values.sortedByDescending { it.second }
            .map { matchCase(typed, it.first) }
            .filter { it != correction && !it.equals(typed, true) }
            .toMutableList()
        // A word that isn't one, read as two with a slip for the space between: never crowded out of the strip.
        if (!known) {
            fixes.firstOrNull { ' ' in it.form }?.let { f ->
                val split = matchCase(typed, f.form)
                if (split != correction && others.remove(split)) others.add(0, split)
            }
        }
        // An acronym space won't force ("ok" -> "OK") is still one tap away, after the top completion.
        val acronym = if (known && correction == null) caseForm(typed)?.takeIf { it.all { c -> c.isUpperCase() } } else null
        if (acronym != null) others.add(min(1, others.size), acronym)

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
        // With no correction the left slot shows the top completion, so its emoji can take the right one: "piz" -> 🍕.
        val emojiFor = if (emojiOn) {
            emoji?.forWord(correction ?: typed) ?: if (correction == null) others.firstOrNull()?.let { emoji?.forWord(it) } else null
        } else {
            null
        }
        val right = when {
            emojiFor != null -> Suggestion(emojiFor, Suggestion.Kind.EMOJI)
            rightWord != null -> Suggestion(rightWord, Suggestion.Kind.COMPLETION)
            else -> null
        }
        val shown = setOfNotNull(left?.text, right?.text)
        val extra = others.filter { it !in shown }.take(extraCount)
            .map { Suggestion(it, Suggestion.Kind.COMPLETION) }
        return Strip(left, center, right, correction, extra)
    }

    /** Next-word predictions after a committed word, or the commonest words with no context. */
    fun predictNext(previous: String?, emojiOn: Boolean): Strip {
        val words = ArrayList<String>()
        if (previous != null) {
            words += learned.next(language, previous, 3 + extraCount)
            for (w in pairs.next(previous, 3 + extraCount)) {
                val form = dictionary.forms(w).firstOrNull()?.word ?: continue
                if (words.none { it.equals(form, true) } && !learned.isBlocked(language, form)) words += form
            }
        }
        for (e in dictionary.top(8 + extraCount * 2)) {
            if (words.size >= 3 + extraCount) break
            if (words.none { it.equals(e.word, true) } && !learned.isBlocked(language, e.word)) words += e.word
        }
        val emojiFor = if (emojiOn && previous != null) emoji?.forWord(previous) else null
        fun p(i: Int) = words.getOrNull(i)?.let { Suggestion(it, Suggestion.Kind.PREDICTION) }
        return Strip(
            left = p(1),
            center = p(0),
            right = emojiFor?.let { Suggestion(it, Suggestion.Kind.EMOJI) } ?: p(2),
            extra = (if (emojiFor != null) 2 else 3).let { from -> (from until from + extraCount).mapNotNull { p(it) } },
        )
    }

    /**
     * A candidate for what was meant: [cost] is the edit distance, [score] the ranking
     * with it. One that is not [force]d is offered in the strip but never autocorrected.
     */
    private class Fix(val form: String, val score: Double, val cost: Double, val force: Boolean = true)

    /** Fuzzy matches for a typed word, best first, each as (form, score). */
    fun corrections(lower: String, previous: String?, touches: List<Touch?>? = null): List<Pair<String, Double>> =
        fixes(lower, previous, touches).map { it.form to it.score }

    private fun fixes(lower: String, previous: String?, touches: List<Touch?>?): List<Fix> {
        if (lower.length < 2 || lower.any { it.isDigit() }) return emptyList()
        val t = touches?.takeIf { it.size == lower.length }
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
        val out = ArrayList<Fix>()
        for (e in dictionary.candidates(firsts, lower.length, 2)) {
            if (e.lower == lower) continue
            val cost = editCost(lower, e.lower, budget, t)
            if (cost <= budget) out += Fix(e.word, prior(e.word, previous) - EDIT_WEIGHT * cost, cost)
        }
        for ((w, _) in learned.all(language)) {
            val l = w.lowercase()
            if (l == lower || kotlin.math.abs(l.length - lower.length) > 2 || base(l[0]) !in firsts) continue
            if (learned.count(language, w) < KNOWN_AFTER) continue
            val cost = editCost(lower, l, budget, t)
            if (cost <= budget) out += Fix(w, prior(w, previous) - EDIT_WEIGHT * cost, cost)
        }
        splits(lower, previous, t, out)
        // One entry per word, in the form that scored best ("leben" vs "Leben").
        return out.sortedByDescending { it.score }.distinctBy { it.form.lowercase() }.take(8)
    }

    /**
     * Two words run together: a thumb aimed at space that landed on a letter above it
     * ("thisnis" -> "this is"), or space missed altogether ("ofthe" -> "of the").
     * Real words missing from the list look just like this ("windburn", "inpatient"):
     * a letter over the space bar between two everyday words of two letters or more is
     * forced (about 0.4% of unlisted dictionary words read that way, mostly rare ones);
     * a one-letter half ("inborn" -> "I born") only when the letter was touched low,
     * toward the space bar. Halves that are listed but not everyday are offered, never forced.
     */
    private fun splits(lower: String, previous: String?, touches: List<Touch?>?, out: MutableList<Fix>) {
        if (lower.length < 3 || !lower.all { it.isLetter() || it == '\'' }) return
        fun pair(left: String, right: String, cost: Double, force: Boolean, rareHalves: Boolean = false) {
            val both = left in everyday && right in everyday
            if (!both && !rareHalves) return
            val l = splitForm(left) ?: return
            val r = splitForm(right) ?: return
            val rare = if (both) 0.0 else RARE_HALF
            out += Fix("$l $r", min(prior(l, previous), prior(r, l)) - EDIT_WEIGHT * cost - SPLIT_PENALTY - rare, cost, force && both)
        }
        for (i in 1 until lower.length - 1) {
            if (lower[i] !in grid.nearSpace) continue
            val t = touches?.getOrNull(i)
            // Low on the key is where a thumb headed for the space bar lands; high on it is the letter.
            val low = t != null && t.dy > LOW_TOUCH
            if (t != null && t.dy < -LOW_TOUCH) continue
            // A one-letter half ("inthe" -> "I the") is the less likely reading of a slip.
            val oneLetter = i == 1 || i == lower.length - 2
            val cost = (if (low) SPACE_SLIP_LOW else SPACE_SLIP) + if (oneLetter) ONE_LETTER_HALF else 0.0
            pair(lower.substring(0, i), lower.substring(i + 1), cost, force = low || !oneLetter, rareHalves = true)
        }
        for (i in 1 until lower.length) {
            val left = lower.substring(0, i)
            val right = lower.substring(i)
            // German writes its compounds in one word: two common words together are a word.
            pair(left, right, MISSED_SPACE, force = language != "de" && left in common && right in common)
        }
    }

    /**
     * One word cut in two by a stray space ("hav e" -> "have"), or by a space hit for a
     * letter above it ("belie e" -> "believe", only when the space was touched [high]). [left] must
     * be no everyday word, nothing the operator types, and far rarer than the whole; the
     * whole must be everyday. So "me n you", "plan b" and "wind burn" stay as they are.
     */
    fun rejoin(left: String, right: String, high: Boolean): String? {
        val l = left.lowercase()
        val r = right.lowercase()
        if (l.isEmpty() || r.isEmpty() || !(l + r).all { it.isLetter() || it == '\'' }) return null
        if (right.any { it.isUpperCase() } || left.drop(1).any { it.isUpperCase() }) return null
        // The left half was itself learned once, when its space was typed.
        if (l in everyday || learned.count(language, l) > 1) return null
        if (r.length > 1 && isKnown(r) && r !in common) return null
        val whole = sequence {
            yield(l + r)
            if (high) for (c in grid.nearSpace) yield(l + c + r)
        }.filter { it in everyday && !learned.isBlocked(language, it) }
            .filter { dictionary.count(it) >= REJOIN_RATIO * max(1, dictionary.count(l)) }
            .maxByOrNull { dictionary.count(it) } ?: return null
        val form = dictionary.forms(whole).firstOrNull { it.word == whole }?.word ?: dictionary.forms(whole).first().word
        return matchCase(left, form)
    }

    /** How far a touch that typed [typed] landed from the center of [meant], with no aim taken out. */
    fun missFrom(typed: Char, touch: Touch, meant: Char): Touch? {
        val a = grid.position(typed) ?: return null
        val b = grid.position(meant) ?: return null
        return Touch(
            (a.first - b.first).toFloat() + touch.dx + touch.aimX,
            (a.second - b.second).toFloat() + touch.dy + touch.aimY,
        )
    }

    /** The form a half of a split word is written in ("i" -> "I"), or null when it isn't a word. */
    private fun splitForm(part: String): String? {
        if (part.length <= 2 && part !in common) return null
        if (!isKnown(part)) return null
        val forms = dictionary.forms(part).filterNot { learned.isBlocked(language, it.word) }
        val form = forms.firstOrNull { it.word == part }?.word ?: forms.firstOrNull()?.word ?: part
        return form.takeUnless { learned.isBlocked(language, it) }
    }

    private val common: Set<String> = dictionary.ranked.take(COMMON).toHashSet()
    private val everyday: Set<String> = dictionary.ranked.toHashSet()

    /**
     * "ks" -> "is": the subtitle word lists hold abbreviations and typos, so a slip onto
     * one counted as known and was never corrected. Real but rare words ("jot") look the
     * same by frequency, so it takes both: the finger landed near the edge toward the
     * other key, and that word is thousands of times commoner.
     */
    private fun shadowFix(typed: String, previous: String?, fixes: List<Fix>): String? {
        if (!canCorrect(typed) || learned.count(language, typed) >= KNOWN_AFTER) return null
        val own = prior(typed, previous)
        val best = fixes.firstOrNull { it.cost <= SHADOW_COST && it.form.length == typed.length && ' ' !in it.form }
            ?: return null
        return if (best.score + EDIT_WEIGHT * best.cost - own >= SHADOW_MARGIN) matchCase(typed, best.form) else null
    }

    /** log-scale likelihood of [word] here: how common it is, how often the operator uses it, and after what. */
    fun prior(word: String, previous: String?): Double {
        val base = ln(1.0 + dictionary.forms(word).firstOrNull { it.word == word }?.count
            .let { it ?: dictionary.count(word) })
        val mine = learned.count(language, word)
        val pair = if (previous != null) learned.pair(language, previous, word) else 0
        val context = if (previous != null) min(MAX_LIFT, pairs.lift(previous, word)) else 0.0
        return base + LEARNED_WEIGHT * ln(1.0 + mine) + PAIR_WEIGHT * ln(1.0 + pair) + context
    }

    /**
     * Weighted Damerau-Levenshtein: a slip onto a neighboring key, a doubled
     * letter, a missing apostrophe or accent all cost less than a random edit.
     * Returns early with [bound] + 1 once no alignment can come in under [bound].
     */
    fun editCost(typed: String, word: String, bound: Double, touches: List<Touch?>? = null): Double {
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
                var best = prev[j - 1] + subCost(a, b, touches?.getOrNull(i - 1))
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

    /**
     * Typing [a] where [b] was meant. With where the finger landed, the cost follows
     * how close it came to [b]: the edge of `g` toward `h` is nearly free, the far
     * side of `g` is no closer to `h` than a whole slip.
     */
    private fun subCost(a: Char, b: Char, touch: Touch? = null): Double = when {
        a == b -> 0.0
        base(a) == base(b) -> ACCENT
        !grid.adjacent(a, b) -> 1.0
        touch == null -> NEIGHBOR
        else -> {
            val ratio = grid.touchDistance(a, touch, b) / grid.distance(a, b)
            (NEIGHBOR * ratio * ratio).coerceIn(MIN_NEIGHBOR, 1.0)
        }
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
     * Only a name-shaped form of three letters or more is forced: the word lists
     * also hold symbols and acronyms ("Th", "Le", "ER", "SAM"), and "th", "er"
     * and "sam" typed in lowercase are meant that way.
     */
    private fun caseFix(typed: String): String? {
        val form = caseForm(typed) ?: return null
        val nameShaped = form.drop(1).none { it.isUpperCase() }
        return form.takeIf {
            nameShaped && (form.length >= 3 || form == "I" || typed in HONORIFICS)
        }
    }

    /** The case the dictionary keeps a lowercase-typed word in, when it has no lowercase form. */
    private fun caseForm(typed: String): String? {
        if (typed.any { it.isUpperCase() } || !typed.all { it.isLetter() || it == '\'' }) return null
        val forms = dictionary.forms(typed).filterNot { learned.isBlocked(language, it.word) }
        if (forms.isEmpty() || forms.any { it.word == typed }) return null
        // Never applied when the operator has typed it in lowercase themselves.
        if (learned.forms(language, typed).any { it == typed }) return null
        return forms.first().word
    }

    /**
     * "hows" -> "how's", "cant" -> "can't": the word list keeps the bare spelling
     * (subtitles misspell it), so it counts as known, but the apostrophe form is so
     * much more common that the bare one is the slip. "its", "lets", "ill", "well"
     * and "were" are real words, too close in frequency to force.
     */
    private fun apostropheFix(typed: String): String? {
        if (!typed.all { it.isLetter() }) return null
        val c = dictionary.contraction(typed) ?: return null
        if (learned.isBlocked(language, c.word)) return null
        if (c.count < APOSTROPHE_RATIO * max(1, dictionary.count(typed))) return null
        // Typed bare often enough on purpose, it is the operator's word.
        if (learned.count(language, typed) >= OWN_BARE) return null
        return matchCase(typed, c.word)
    }

    private fun canCorrect(typed: String): Boolean =
        typed.length >= 2 && typed.all { it.isLetter() || it == '\'' } &&
            // All-caps is deliberate (an acronym), and so is mixed case after the first letter.
            !(typed.length > 1 && typed.drop(1).any { it.isUpperCase() })

    companion object {
        const val KNOWN_AFTER = 2
        private val HONORIFICS = setOf("mr", "mrs", "ms", "dr", "jr", "sr", "st")
        /** Suggestions on the second row. */
        const val EXTRA = 3
        /** Words beyond the strip's three that the word grid asks for. */
        const val GRID_EXTRA = 21
        private const val EDIT_WEIGHT = 4.5
        private const val COMPLETION_PENALTY = 1.5
        private const val LEARNED_WEIGHT = 2.5
        private const val PAIR_WEIGHT = 2.0
        /** The most the bundled word pairs can add: e^4, about 50 times likelier. */
        private const val MAX_LIFT = 4.0
        /** How much commoner a rejoined word must be than its left half. */
        private const val REJOIN_RATIO = 1000
        private const val NEIGHBOR = 0.6
        /** A tap right on the border between two keys still prefers the key it hit. */
        private const val MIN_NEIGHBOR = 0.1
        /** A letter over the space bar typed for a space; low on the key, cheaper. */
        private const val SPACE_SLIP = 0.5
        private const val SPACE_SLIP_LOW = 0.25
        private const val LOW_TOUCH = 0.2f
        private const val MISSED_SPACE = 0.4
        private const val ONE_LETTER_HALF = 0.5
        private const val SPLIT_PENALTY = 1.5
        /** A split into a listed but not everyday word ("in boston"): offered, ranked lower. */
        private const val RARE_HALF = 1.0
        /** Words this high in the list, run together with no space, are taken apart by the space bar. */
        private const val COMMON = 300
        /** A known word is corrected only by one touch near the edge, below the cost of a centered one ([NEIGHBOR]). */
        private const val SHADOW_COST = 0.35
        /** ln(4000): how much commoner the other word must be. */
        private const val SHADOW_MARGIN = 8.3
        private const val ACCENT = 0.2
        private const val APOSTROPHE = 0.15
        /** How much more common the apostrophe form must be before a known bare spelling is corrected. */
        private const val APOSTROPHE_RATIO = 100
        private const val OWN_BARE = 20
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
