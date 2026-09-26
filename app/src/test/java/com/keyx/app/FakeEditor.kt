package com.keyx.app

import android.view.KeyEvent
import com.keyx.app.ime.Editor

/** An InputConnection's semantics over a StringBuilder: composing region, cursor, commit. */
class FakeEditor(initial: String = "") : Editor {
    val text = StringBuilder(initial)
    var cursor = initial.length
    private var compStart = -1
    private var compEnd = -1
    val actions = ArrayList<Int>()

    val composing: String get() = if (compStart < 0) "" else text.substring(compStart, compEnd)

    override fun before(n: Int) = text.substring(maxOf(0, cursor - n), cursor)
    override fun after(n: Int) = text.substring(cursor, minOf(text.length, cursor + n))

    override fun setComposing(text: String) {
        replaceRegion(text)
        compEnd = cursor
        compStart = cursor - text.length
        if (text.isEmpty()) { compStart = -1; compEnd = -1 }
    }

    override fun finishComposing() { compStart = -1; compEnd = -1 }

    override fun commit(text: String) {
        replaceRegion(text)
        compStart = -1; compEnd = -1
    }

    private fun replaceRegion(s: String) {
        if (compStart >= 0) {
            text.replace(compStart, compEnd, s)
            cursor = compStart + s.length
        } else {
            text.insert(cursor, s)
            cursor += s.length
        }
    }

    override fun deleteBefore(n: Int) {
        val start = maxOf(0, cursor - n)
        text.delete(start, cursor)
        cursor = start
    }

    override fun key(keyCode: Int) {
        if (keyCode == KeyEvent.KEYCODE_DEL && cursor > 0) {
            val n = Character.charCount(text.codePointBefore(cursor))
            deleteBefore(n)
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && cursor > 0) cursor--
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && cursor < text.length) cursor++
    }

    override fun editorAction(action: Int) { actions += action }

    /** Sentence caps: at the start, or after ". " / "! " / "? ". */
    override fun capsMode(reqModes: Int): Int {
        val b = before(3).trimEnd(' ')
        val atStart = before(cursor).isBlank()
        return if (atStart || (b.isNotEmpty() && b.last() in ".!?" && before(1) == " ")) reqModes else 0
    }

    override fun batch(block: () -> Unit) = block()

    override fun toString() = text.toString()
}
