package com.keyx.app.ime

/**
 * Where KeyX expects the cursor to be, from the edits it has sent.
 *
 * The text field reports selection changes asynchronously. Typing fast means
 * several edits are in flight, and the reports for the older ones arrive after
 * newer edits were sent. Without this, such a late report reads as "the user
 * moved the cursor", the word being typed is abandoned mid-way, and fast typing
 * falls apart. A report is a real move only if it matches no position one of
 * KeyX's own recent edits produced.
 */
class CursorTracker {
    private var cursor: Int? = null
    private var composingLength = 0
    private val recent = ArrayDeque<Int>()

    fun reset(position: Int?) {
        cursor = position?.takeIf { it >= 0 }
        composingLength = 0
        recent.clear()
        cursor?.let { recent.addLast(it) }
    }

    fun setComposing(length: Int) = move { c ->
        val start = c - composingLength
        composingLength = length
        start + length
    }

    fun finishComposing() { composingLength = 0 }

    /** Text already before the cursor became the composing region; the cursor did not move. */
    fun composeBefore(length: Int) { if (cursor != null) composingLength = length }

    fun commit(length: Int) = move { c ->
        val start = c - composingLength
        composingLength = 0
        start + length
    }

    fun deleteBefore(n: Int) = move { c -> (c - n).coerceAtLeast(0) }

    /** A key event (backspace, arrows): the result is up to the app; resynchronize from its next report. */
    fun unknown() {
        cursor = null
        composingLength = 0
    }

    /** True when the report is the user moving the cursor, not an echo of KeyX's own edits. */
    fun onUpdate(selStart: Int, selEnd: Int): Boolean {
        if (selStart != selEnd) {
            // KeyX never selects text; a selection is always the user.
            unknown()
            recent.clear()
            return true
        }
        val c = cursor
        if (c == null) {
            cursor = selEnd
            recent.clear()
            recent.addLast(selEnd)
            return false
        }
        if (selEnd == c) {
            // Caught up: every older report has now arrived.
            recent.clear()
            recent.addLast(c)
            return false
        }
        if (selEnd in recent) return false
        cursor = selEnd
        composingLength = 0
        recent.clear()
        recent.addLast(selEnd)
        return true
    }

    private inline fun move(to: (Int) -> Int) {
        val c = cursor ?: return
        val n = to(c)
        cursor = n
        recent.addLast(n)
        while (recent.size > MAX_IN_FLIGHT) recent.removeFirst()
    }

    private companion object {
        const val MAX_IN_FLIGHT = 32
    }
}
