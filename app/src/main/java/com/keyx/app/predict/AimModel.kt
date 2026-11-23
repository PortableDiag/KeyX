package com.keyx.app.predict

import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.min

/**
 * Where the operator's finger really lands: the average miss from the center of the
 * letter that was meant, for each half of each letter row — the two thumbs miss
 * differently, and reaching up misses differently from reaching down. It holds
 * averages only, so nothing of what was typed can be read back from it.
 */
class AimModel {
    private class Zone(var n: Int = 0, var dx: Float = 0f, var dy: Float = 0f)
    private val zones = Array(ZONES) { Zone() }

    val samples: Int get() = zones.sumOf { it.n }

    /** One letter: the finger landed ([dx], [dy]) from the center of the letter that was meant. */
    fun learn(zone: Int, dx: Float, dy: Float) {
        val z = zones.getOrNull(zone) ?: return
        // That far off isn't aim, it's another word.
        if (abs(dx) > MAX_MISS || abs(dy) > MAX_MISS) return
        z.n = min(z.n + 1, MEMORY)
        z.dx += (dx - z.dx) / z.n
        z.dy += (dy - z.dy) / z.n
    }

    /** The operator's usual miss in [zone]; subtract it from a touch. Nothing until there is enough to go by. */
    fun offset(zone: Int): Touch {
        val z = zones.getOrNull(zone) ?: return NONE
        if (z.n < MIN_SAMPLES) return NONE
        return Touch(z.dx.coerceIn(-MAX_SHIFT, MAX_SHIFT), z.dy.coerceIn(-MAX_SHIFT, MAX_SHIFT))
    }

    /** The average over every zone, for Settings. */
    fun average(): Touch {
        val n = samples
        if (n == 0) return NONE
        return Touch(zones.map { it.dx * it.n }.sum() / n, zones.map { it.dy * it.n }.sum() / n)
    }

    fun clear() = zones.forEach { it.n = 0; it.dx = 0f; it.dy = 0f }

    fun toJson(): JSONArray = JSONArray().also { a ->
        for (z in zones) a.put(JSONArray().put(z.n).put(z.dx.toDouble()).put(z.dy.toDouble()))
    }

    fun load(json: JSONArray?) {
        clear()
        if (json == null || json.length() != ZONES) return
        for (i in 0 until ZONES) {
            val z = json.optJSONArray(i) ?: continue
            zones[i].n = z.optInt(0).coerceIn(0, MEMORY)
            zones[i].dx = z.optDouble(1, 0.0).toFloat().coerceIn(-MAX_MISS, MAX_MISS)
            zones[i].dy = z.optDouble(2, 0.0).toFloat().coerceIn(-MAX_MISS, MAX_MISS)
        }
    }

    companion object {
        private const val ROWS = 3
        const val ZONES = ROWS * 2
        const val MIN_SAMPLES = 40
        private const val MEMORY = 400
        private const val MAX_MISS = 0.8f
        const val MAX_SHIFT = 0.25f
        private val NONE = Touch(0f, 0f)

        /** -1 for anything that isn't one of the three letter rows. */
        fun zone(row: Int, rightHalf: Boolean): Int = if (row in 0 until ROWS) row * 2 + (if (rightHalf) 1 else 0) else -1
    }
}
