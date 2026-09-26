package com.keyx.app

import com.keyx.app.ime.CursorTracker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorTrackerTest {
    @Test
    fun lateReportsOfOwnEditsAreNotCursorMoves() {
        val t = CursorTracker()
        t.reset(0)
        // Typing "hello" fast: five composing updates sent before any report arrives.
        for (n in 1..5) t.setComposing(n)
        t.commit(6) // "hello "
        t.setComposing(1) // "w"
        // Reports for the older states now trickle in, in order.
        for (pos in listOf(1, 2, 3, 4, 5, 6)) assertFalse("report at $pos", t.onUpdate(pos, pos))
        assertFalse(t.onUpdate(7, 7))
    }

    @Test
    fun aTapElsewhereIsAMove() {
        val t = CursorTracker()
        t.reset(10)
        t.setComposing(3)
        assertFalse(t.onUpdate(13, 13))
        assertTrue(t.onUpdate(2, 2))
        assertTrue(t.onUpdate(2, 5)) // a selection is always the user
    }

    @Test
    fun afterAKeyEventTheNextReportResynchronizes() {
        val t = CursorTracker()
        t.reset(5)
        t.unknown() // backspace sent as a key
        assertFalse(t.onUpdate(4, 4))
        t.commit(1)
        assertFalse(t.onUpdate(5, 5))
        assertTrue(t.onUpdate(0, 0))
    }
}
