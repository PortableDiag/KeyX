package com.keyx.app.ime

import kotlin.math.abs

/**
 * SwiftKey's quick punctuation on the period key: tap for ".", a quick slide
 * right for "?", a quick slide left for "!". No long-press needed.
 */
object PeriodFlick {
    private const val MIN_KEYS = 0.5f
    private const val MAX_MS = 400L

    /** The character a release on the period key types, or null for a plain "." tap. */
    fun onRelease(dx: Float, dy: Float, durationMs: Long, keyWidth: Float): String? {
        if (durationMs > MAX_MS || abs(dx) < MIN_KEYS * keyWidth || abs(dx) < 2 * abs(dy)) return null
        return if (dx > 0) "?" else "!"
    }
}
