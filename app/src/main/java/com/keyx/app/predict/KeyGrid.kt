package com.keyx.app.predict

import kotlin.math.hypot

/**
 * Where each letter sits on the current layout, in key widths. It is what makes
 * "tge" one cheap slip from "the" rather than a full substitution: g is next to h.
 */
class KeyGrid(rows: List<List<String>>) {
    private val pos = HashMap<Char, Pair<Double, Double>>()

    init {
        val widest = rows.maxOfOrNull { it.size } ?: 0
        rows.forEachIndexed { r, keys ->
            val offset = (widest - keys.size) / 2.0
            keys.forEachIndexed { c, key ->
                if (key.length == 1) pos[key[0].lowercaseChar()] = (offset + c + 0.5) to r.toDouble()
            }
        }
    }

    fun position(c: Char): Pair<Double, Double>? = pos[c.lowercaseChar()]

    fun distance(a: Char, b: Char): Double {
        val pa = pos[a.lowercaseChar()] ?: return Double.MAX_VALUE
        val pb = pos[b.lowercaseChar()] ?: return Double.MAX_VALUE
        return hypot(pa.first - pb.first, pa.second - pb.second)
    }

    fun adjacent(a: Char, b: Char): Boolean = distance(a, b) <= 1.2

    val letters: Set<Char> get() = pos.keys
}
