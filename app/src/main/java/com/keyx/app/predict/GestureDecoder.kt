package com.keyx.app.predict

import kotlin.math.hypot
import kotlin.math.ln

/**
 * Swipe ("flow") typing: turns a finger trace across the keys into words.
 *
 * Candidates are the dictionary words whose first and last letters sit near the
 * trace's start and end. Each one's ideal trace — straight lines between its key
 * centers — is resampled to the same number of points as the real one and the
 * two are compared point by point. Every letter must also be passed near, in
 * order, which is what separates "hello" from "ho".
 *
 * Coordinates are in pixels; [keyWidth] scales every tolerance.
 */
class GestureDecoder(
    private val keyCenters: Map<Char, Pair<Float, Float>>,
    private val keyWidth: Float,
) {
    data class Point(val x: Float, val y: Float)

    fun decode(
        trace: List<Point>,
        dictionary: Dictionary,
        bonus: (String) -> Double = { 0.0 },
        limit: Int = 5,
    ): List<String> {
        if (trace.size < 2) return emptyList()
        val starts = near(trace.first(), START_RADIUS)
        val ends = near(trace.last(), START_RADIUS)
        val sampled = resample(trace, SAMPLES)
        val scored = ArrayList<Pair<String, Double>>()
        for (s in starts) {
            val byLast = dictionary.byEnds[s] ?: continue
            for (e in ends) {
                for (entry in byLast[e].orEmpty()) {
                    val ideal = idealTrace(entry.lower) ?: continue
                    if (!passesLetters(entry.lower, trace)) continue
                    val shape = meanDistance(sampled, resample(ideal, SAMPLES)) / keyWidth
                    val score = -SHAPE_WEIGHT * shape + FREQ_WEIGHT * ln(1.0 + entry.count) + bonus(entry.word)
                    scored += entry.word to score
                }
            }
        }
        return scored.sortedByDescending { it.second }.map { it.first }
            .distinctBy { it.lowercase() }.take(limit)
    }

    private fun near(p: Point, radius: Float): List<Char> =
        keyCenters.filter { (_, c) -> hypot(c.first - p.x, c.second - p.y) <= radius * keyWidth }
            .keys.toList()

    private fun idealTrace(word: String): List<Point>? {
        val pts = ArrayList<Point>()
        var last: Char? = null
        for (ch in word) {
            if (ch == last) continue // "hello": the double l is one key visit
            val c = keyCenters[ch] ?: return null
            pts += Point(c.first, c.second)
            last = ch
        }
        if (pts.size == 1) pts += pts[0]
        return pts
    }

    private fun passesLetters(word: String, trace: List<Point>): Boolean {
        var i = 0
        for (ch in word) {
            val c = keyCenters[ch] ?: return false
            val limit = LETTER_RADIUS * keyWidth
            while (i < trace.size && hypot(trace[i].x - c.first, trace[i].y - c.second) > limit) i++
            if (i == trace.size) return false
        }
        return true
    }

    companion object {
        private const val SAMPLES = 32
        private const val START_RADIUS = 1.1f
        private const val LETTER_RADIUS = 0.9f
        private const val SHAPE_WEIGHT = 6.0
        private const val FREQ_WEIGHT = 0.35

        fun resample(points: List<Point>, n: Int): List<Point> {
            var total = 0f
            for (i in 1 until points.size) total += dist(points[i - 1], points[i])
            if (total == 0f) return List(n) { points[0] }
            val step = total / (n - 1)
            val out = ArrayList<Point>(n)
            out += points[0]
            var acc = 0f
            var prev = points[0]
            var i = 1
            while (i < points.size && out.size < n) {
                val cur = points[i]
                val d = dist(prev, cur)
                if (acc + d >= step && d > 0f) {
                    val t = (step - acc) / d
                    val q = Point(prev.x + t * (cur.x - prev.x), prev.y + t * (cur.y - prev.y))
                    out += q
                    prev = q
                    acc = 0f
                } else {
                    acc += d
                    prev = cur
                    i++
                }
            }
            while (out.size < n) out += points.last()
            return out
        }

        fun meanDistance(a: List<Point>, b: List<Point>): Float {
            var sum = 0f
            for (i in a.indices) sum += dist(a[i], b[i])
            return sum / a.size
        }

        private fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
    }
}
