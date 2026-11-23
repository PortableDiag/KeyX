package com.keyx.app.predict

import kotlin.math.hypot

/**
 * Where a key was touched: the offset of the finger from the key's center, in key
 * widths across and key heights down. It is what lets a tap on the edge of `g`
 * count as nearly an `h`, as SwiftKey's touch model does, instead of a whole slip.
 */
data class Touch(
    val dx: Float,
    val dy: Float,
    /** The part of the row it landed in, for [AimModel]; -1 when aim isn't learned from it. */
    val zone: Int = -1,
    /** The aim already taken out of [dx] and [dy]. */
    val aimX: Float = 0f,
    val aimY: Float = 0f,
)

/**
 * Where each letter sits on the current layout, in key widths. It is what makes
 * "tge" one cheap slip from "the" rather than a full substitution: g is next to h.
 */
class KeyGrid(rows: List<List<String>>) {
    private val pos = HashMap<Char, Pair<Double, Double>>()

    /**
     * Bottom-row letters over the space bar, or at its end (c v b n, and m, on QWERTY):
     * a thumb reaching for space lands on them, so "thisnis" is "this is".
     */
    val nearSpace: Set<Char>

    init {
        val widest = rows.maxOfOrNull { it.size } ?: 0
        rows.forEachIndexed { r, keys ->
            val offset = (widest - keys.size) / 2.0
            keys.forEachIndexed { c, key ->
                if (key.length == 1) pos[key[0].lowercaseChar()] = (offset + c + 0.5) to r.toDouble()
            }
        }
        val last = rows.lastIndex.toDouble()
        // The space bar runs from about a third of the way across to the period key.
        val w = widest.coerceAtLeast(10)
        nearSpace = pos.filterValues { it.second == last && it.first >= w * 0.35 && it.first <= w * 0.85 }.keys
    }

    fun position(c: Char): Pair<Double, Double>? = pos[c.lowercaseChar()]

    fun distance(a: Char, b: Char): Double {
        val pa = pos[a.lowercaseChar()] ?: return Double.MAX_VALUE
        val pb = pos[b.lowercaseChar()] ?: return Double.MAX_VALUE
        return hypot(pa.first - pb.first, pa.second - pb.second)
    }

    /** How far the finger that typed [typed] landed from the center of [other], in key widths. */
    fun touchDistance(typed: Char, touch: Touch, other: Char): Double {
        val pa = pos[typed.lowercaseChar()] ?: return Double.MAX_VALUE
        val pb = pos[other.lowercaseChar()] ?: return Double.MAX_VALUE
        return hypot(pa.first + touch.dx - pb.first, pa.second + touch.dy - pb.second)
    }

    fun adjacent(a: Char, b: Char): Boolean = distance(a, b) <= 1.2

    val letters: Set<Char> get() = pos.keys
}
