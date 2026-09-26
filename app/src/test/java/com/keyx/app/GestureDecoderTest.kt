package com.keyx.app

import com.keyx.app.predict.GestureDecoder
import com.keyx.app.predict.GestureDecoder.Point
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class GestureDecoderTest {
    private val key = 100f
    private val layout = TestData.layout("en_US")
    private val centers = HashMap<Char, Pair<Float, Float>>().apply {
        val widest = layout.rows.maxOf { it.size }
        layout.rows.forEachIndexed { r, row ->
            val offset = (widest - row.size) / 2f
            row.forEachIndexed { c, k -> put(k[0], (offset + c + 0.5f) * key to (r + 0.5f) * key) }
        }
    }
    private val decoder = GestureDecoder(centers, key)
    private val dict = TestData.dictionary("en")

    /** A wobbly human-ish trace through the word's keys. */
    private fun trace(word: String, seed: Int = 1): List<Point> {
        val rnd = Random(seed)
        val pts = ArrayList<Point>()
        val keys = word.map { centers.getValue(it) }
        for (i in 0 until keys.size - 1) {
            val (x0, y0) = keys[i]
            val (x1, y1) = keys[i + 1]
            for (s in 0 until 12) {
                val t = s / 12f
                pts += Point(x0 + (x1 - x0) * t + rnd.nextFloat() * 20 - 10, y0 + (y1 - y0) * t + rnd.nextFloat() * 20 - 10)
            }
        }
        pts += keys.last().let { Point(it.first, it.second) }
        return pts
    }

    @Test
    fun decodesCommonWords() {
        for (w in listOf("hello", "the", "keyboard", "thanks", "world", "phone")) {
            assertEquals(w, decoder.decode(trace(w), dict).firstOrNull()?.lowercase())
        }
    }

    @Test
    fun offersAlternatives() {
        val out = decoder.decode(trace("good"), dict)
        assertEquals("good", out.first())
        assert(out.size > 1)
    }
}
