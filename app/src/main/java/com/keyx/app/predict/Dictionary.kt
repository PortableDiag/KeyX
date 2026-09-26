package com.keyx.app.predict

import java.io.BufferedReader
import kotlin.math.ln

/**
 * A language's word list: `word<TAB>count`, most frequent first, as built by
 * tools/build_dicts.py. Lookups are case-insensitive; a word can exist in more
 * than one case ("leben" and "Leben"), and each form keeps its own count.
 */
class Dictionary(entries: List<Pair<String, Int>>) {
    class Entry(val word: String, val lower: String, val count: Int)

    /** Sorted by [Entry.lower], so a prefix is one contiguous range. */
    private val sorted: Array<Entry>
    private val byLower: Map<String, List<Entry>>

    /** Every entry by the unaccented lowercase form of its first letter, for fuzzy matching. */
    private val byFirst: Map<Char, List<Entry>>

    /** Completions for one- and two-letter prefixes are asked for on every word; keep them. */
    private val shortPrefixes = HashMap<String, List<Entry>>()

    /** Letters-only words indexed by first and last letter, for the gesture decoder. */
    val byEnds: Map<Char, Map<Char, List<Entry>>>

    val size: Int get() = sorted.size
    val maxCount: Int

    init {
        sorted = entries.map { (w, c) -> Entry(w, w.lowercase(), c) }
            .sortedBy { it.lower }.toTypedArray()
        byLower = sorted.groupBy { it.lower }
            .mapValues { (_, forms) -> forms.sortedByDescending { it.count } }
        maxCount = sorted.maxOfOrNull { it.count } ?: 1
        byFirst = sorted.groupBy { Suggester.base(it.lower[0]) }
        val ends = HashMap<Char, HashMap<Char, MutableList<Entry>>>()
        for (e in sorted) {
            if (e.lower.length < 2 || !e.lower.all { it.isLetter() }) continue
            ends.getOrPut(e.lower.first()) { HashMap() }
                .getOrPut(e.lower.last()) { ArrayList() }.add(e)
        }
        byEnds = ends
    }

    fun contains(word: String): Boolean = byLower.containsKey(word.lowercase())

    /** Every stored case of [word], most frequent first; empty if unknown. */
    fun forms(word: String): List<Entry> = byLower[word.lowercase()].orEmpty()

    fun count(word: String): Int = forms(word).firstOrNull()?.count ?: 0

    /** 0..1, logarithmic, so a word 10x as common scores a fixed step higher. */
    fun score(count: Int): Double = ln(1.0 + count) / ln(1.0 + maxCount)

    /** Words starting with [prefix] (case-insensitive), most frequent first. */
    fun completions(prefix: String, limit: Int): List<Entry> {
        val p = prefix.lowercase()
        if (p.isEmpty()) return emptyList()
        if (p.length <= 2) {
            val top = shortPrefixes.getOrPut(p) { scan(p, SHORT_PREFIX_KEEP) }
            return if (top.size > limit) top.subList(0, limit) else top
        }
        return scan(p, limit)
    }

    private fun scan(p: String, limit: Int): List<Entry> {
        var lo = lowerBound(p)
        val out = ArrayList<Entry>()
        while (lo < sorted.size && sorted[lo].lower.startsWith(p)) {
            out.add(sorted[lo]); lo++
        }
        out.sortByDescending { it.count }
        return if (out.size > limit) out.subList(0, limit) else out
    }

    /** Candidate words for fuzzy matching: entries starting with one of [firsts], within [slack] letters of [length]. */
    fun candidates(firsts: Collection<Char>, length: Int, slack: Int): Sequence<Entry> =
        firsts.asSequence().flatMap { byFirst[it].orEmpty().asSequence() }
            .filter { kotlin.math.abs(it.lower.length - length) <= slack }

    /** The [n] most frequent words, for predictions with no context at all. */
    fun top(n: Int): List<Entry> = topWords.take(n)

    private val topWords: List<Entry> by lazy { sorted.sortedByDescending { it.count }.take(TOP) }

    private fun lowerBound(key: String): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid].lower < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        private const val TOP = 64
        private const val SHORT_PREFIX_KEEP = 16

        fun parse(reader: BufferedReader): Dictionary {
            val entries = ArrayList<Pair<String, Int>>(40_000)
            reader.forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) {
                    val count = line.substring(tab + 1).trim().toIntOrNull()
                    if (count != null) entries.add(line.substring(0, tab) to count)
                }
            }
            return Dictionary(entries)
        }
    }
}
