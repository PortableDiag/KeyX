package com.keyx.app.ime

import kotlin.math.abs

/**
 * The space bar carries two gestures that would otherwise collide, resolved the
 * way SwiftKey resolves them: a quick horizontal flick switches language; a
 * long-press followed by a drag moves the cursor. Anything else is a space.
 */
object SpaceGesture {
    enum class Result { SPACE, LANGUAGE_NEXT, LANGUAGE_PREVIOUS, NONE }

    const val LONG_PRESS_MS = 350L
    private const val FLICK_MAX_MS = 450L
    private const val FLICK_MIN_KEYS = 1.0f
    private const val TAP_SLOP_KEYS = 0.5f

    /** Chars of cursor travel per key width of drag. */
    const val CURSOR_STEP_KEYS = 0.33f

    fun onRelease(
        dx: Float,
        dy: Float,
        durationMs: Long,
        cursorMoved: Boolean,
        keyWidth: Float,
        languages: Int,
    ): Result {
        if (cursorMoved) return Result.NONE
        val flick = abs(dx) >= FLICK_MIN_KEYS * keyWidth && abs(dx) > 2 * abs(dy) && durationMs <= FLICK_MAX_MS
        if (flick) {
            if (languages < 2) return Result.NONE
            return if (dx > 0) Result.LANGUAGE_NEXT else Result.LANGUAGE_PREVIOUS
        }
        return if (abs(dx) < TAP_SLOP_KEYS * keyWidth * 2 && abs(dy) < keyWidth) Result.SPACE else Result.NONE
    }

    /** Whether a long-press should start cursor control: the finger has not moved yet. */
    fun startsCursor(dx: Float, dy: Float, keyWidth: Float): Boolean =
        abs(dx) < TAP_SLOP_KEYS * keyWidth && abs(dy) < TAP_SLOP_KEYS * keyWidth

    /** Total cursor steps for a drag of [dx] since cursor control began; negative is left. */
    fun cursorSteps(dx: Float, keyWidth: Float): Int = (dx / (CURSOR_STEP_KEYS * keyWidth)).toInt()
}
