package com.keyx.app.ime

import android.view.KeyEvent
import com.keyx.app.predict.LearnedModel
import com.keyx.app.predict.Strip
import com.keyx.app.predict.Suggester
import com.keyx.app.predict.Suggestion

/** The text field, as the engine sees it. The service adapts an InputConnection to it. */
interface Editor {
    fun before(n: Int): String
    fun after(n: Int): String
    fun setComposing(text: String)
    fun finishComposing()
    fun commit(text: String)
    fun deleteBefore(n: Int)
    /** Make [word], the text just before the cursor, the composing region — without rewriting it. */
    fun composeBefore(word: String)
    fun key(keyCode: Int)
    fun editorAction(action: Int)
    fun capsMode(reqModes: Int): Int
    fun batch(block: () -> Unit)
}

data class EngineOptions(
    val autocorrect: Boolean = true,
    val predictions: Boolean = true,
    val emojiPredictions: Boolean = true,
    val autoCaps: Boolean = true,
    val doubleSpacePeriod: Boolean = true,
    val spaceAfterPunctuation: Boolean = true,
)

enum class Shift { OFF, ONCE, LOCKED }

/**
 * Every editing decision the keyboard makes: the word being composed, when
 * autocorrect fires and how backspace undoes it, auto-caps, smart punctuation,
 * swiped words and what gets learned. No Android views, so all of it is tested
 * on the JVM against a fake editor.
 */
class InputEngine(
    private val learned: LearnedModel,
    private val onLearned: () -> Unit = {},
) {
    var editor: Editor? = null
    var suggester: Suggester? = null
    var options = EngineOptions()
    var policy = FieldPolicy.from(android.text.InputType.TYPE_CLASS_TEXT, 0)
    var listener: (() -> Unit)? = null

    var shift = Shift.OFF
        private set
    private var autoShifted = false

    var strip: Strip = Strip.EMPTY
        private set

    private val composing = StringBuilder()
    val composingText: String get() = composing.toString()

    /** The word before the composing one, read once when the word starts. */
    private var composingPrevious: String? = null

    /** The composing word came from a swipe: it is committed whole, and backspace removes it whole. */
    private var fromGesture = false
    private var gestureAlternatives: List<String> = emptyList()

    private data class Correction(val original: String, val replacement: String, val separator: String)
    private var lastCorrection: Correction? = null

    /** A word the operator reverted a correction on; it is not corrected again this time. */
    private var keepAsTyped: String? = null

    /** The space before the cursor was inserted after a word, so punctuation may swap with it. */
    private var autoSpace = false
    private var lastWasSpace = false

    /**
     * The space before the cursor was added after a punctuation mark: a digit takes
     * it back ("3.14", "1,000", "10:30"), and a space or enter doesn't double it.
     */
    private var spaceAfterMark = false

    private val language: String get() = suggester?.language ?: ""

    fun startInput(policy: FieldPolicy) {
        this.policy = policy
        composing.clear()
        fromGesture = false
        lastCorrection = null
        keepAsTyped = null
        autoSpace = false
        lastWasSpace = false
        spaceAfterMark = false
        if (shift != Shift.LOCKED) shift = Shift.OFF
        refreshCaps()
        refreshStrip()
    }

    // ---- keys -------------------------------------------------------------

    fun onText(text: String) {
        val ed = editor ?: return
        val markSpace = takeMarkSpace(ed)
        if (text.length == 1 && isWordChar(text[0], composing.isNotEmpty())) {
            ed.batch {
                if (markSpace && text[0].isDigit() && ed.before(2).take(1) in listOf(".", ",", ":")) ed.deleteBefore(1)
                if (fromGesture) commitComposing(" ", correct = false)
                // Letters typed onto the end of a word extend that word: erase "Boating"
                // back to "Boa", type "ring", and the word is "Boaring", not "ring".
                if (composing.isEmpty()) adoptWordBeforeCursor()
                if (composing.isEmpty()) composingPrevious = previousWordIn(ed.before(64))
                composing.append(applyShift(text))
                ed.setComposing(composing.toString())
            }
            if (shift == Shift.ONCE) shift = Shift.OFF
            lastCorrection = null
            autoSpace = false
            lastWasSpace = false
        } else {
            ed.batch {
                commitComposing("", correct = !fromGesture && text in CORRECTING_PUNCTUATION)
                if (text in SWAPPING_PUNCTUATION && autoSpace && ed.before(1) == " ") {
                    // "word ." -> "word. " — the space the keyboard added moves after the mark.
                    ed.deleteBefore(1)
                    ed.commit("$text ")
                    autoSpace = true
                    spaceAfterMark = spacesAfterMarks()
                } else if (text in SWAPPING_PUNCTUATION && spacesAfterMarks() && endsAMarkable(ed.before(1))) {
                    // "word," -> "word, " — the next word needs no space key.
                    ed.commit("$text ")
                    autoSpace = true
                    spaceAfterMark = true
                } else {
                    if (markSpace && text in CLOSING_BRACKETS) {
                        // "(see above.)": the bracket closes up against the mark.
                        ed.deleteBefore(1)
                        ed.commit("$text ")
                        spaceAfterMark = true
                    } else {
                        ed.commit(text)
                    }
                    autoSpace = false
                }
            }
            lastWasSpace = false
            if (shift == Shift.ONCE && !autoShifted) shift = Shift.OFF
        }
        refreshCaps()
        refreshStrip()
    }

    fun onSpace() {
        val ed = editor ?: return
        if (takeMarkSpace(ed) && composing.isEmpty()) {
            // The mark already brought its space; this one is the habit of typing it.
            lastWasSpace = false
            finishSpace()
            return
        }
        ed.batch {
            when {
                composing.isNotEmpty() -> {
                    commitComposing(" ", correct = !fromGesture)
                    autoSpace = true
                }
                options.doubleSpacePeriod && lastWasSpace && autoSpace && endsWithWordThenSpace(ed.before(2)) -> {
                    ed.deleteBefore(1)
                    ed.commit(". ")
                    autoSpace = true
                    lastWasSpace = false
                    finishSpace()
                    return@batch
                }
                else -> {
                    ed.commit(" ")
                    autoSpace = false
                }
            }
            lastWasSpace = true
        }
        finishSpace()
    }

    private fun finishSpace() {
        if (shift == Shift.ONCE && !autoShifted) shift = Shift.OFF
        refreshCaps()
        refreshStrip()
    }

    fun onEnter() {
        val ed = editor ?: return
        val markSpace = takeMarkSpace(ed)
        ed.batch {
            if (markSpace && composing.isEmpty()) ed.deleteBefore(1)
            commitComposing("", correct = !fromGesture)
            val action = policy.enterAction
            if (action != null) ed.editorAction(action) else ed.commit("\n")
        }
        autoSpace = false
        lastWasSpace = false
        refreshCaps()
        refreshStrip()
    }

    fun onBackspace() {
        val ed = editor ?: return
        spaceAfterMark = false
        val undo = lastCorrection
        lastWasSpace = false
        if (undo != null && ed.before(undo.replacement.length + undo.separator.length) == undo.replacement + undo.separator) {
            // Backspace straight after a correction puts back what was typed, and keeps it.
            ed.batch {
                ed.deleteBefore(undo.replacement.length + undo.separator.length)
                composingPrevious = previousWordIn(ed.before(64))
                composing.setLength(0)
                composing.append(undo.original)
                ed.setComposing(undo.original)
            }
            keepAsTyped = undo.original
            lastCorrection = null
            autoSpace = false
            refreshStrip()
            return
        }
        lastCorrection = null
        autoSpace = false
        when {
            fromGesture -> {
                composing.setLength(0)
                fromGesture = false
                ed.batch { ed.setComposing(""); ed.finishComposing() }
            }
            composing.isNotEmpty() -> {
                composing.setLength(composing.length - Character.charCount(composing.codePointBefore(composing.length)))
                ed.batch {
                    if (composing.isEmpty()) {
                        ed.setComposing("")
                        ed.finishComposing()
                    } else {
                        ed.setComposing(composing.toString())
                    }
                }
            }
            else -> {
                ed.key(KeyEvent.KEYCODE_DEL)
                resumeWord()
            }
        }
        refreshCaps()
        refreshStrip()
    }

    /** Backspace swiped left: the whole previous word, and the spaces after it. */
    fun onDeleteWord() {
        val ed = editor ?: return
        spaceAfterMark = false
        lastCorrection = null
        autoSpace = false
        lastWasSpace = false
        if (composing.isNotEmpty()) {
            composing.setLength(0)
            fromGesture = false
            ed.batch { ed.setComposing(""); ed.finishComposing() }
        } else {
            val n = wordDeleteLength(ed.before(64))
            if (n > 0) ed.deleteBefore(n)
        }
        refreshCaps()
        refreshStrip()
    }

    fun onPick(s: Suggestion) {
        val ed = editor ?: return
        spaceAfterMark = false
        ed.batch {
            when (s.kind) {
                Suggestion.Kind.EMOJI -> {
                    if (composing.isNotEmpty()) {
                        commitComposing(" ", correct = false)
                    } else {
                        val before = ed.before(1)
                        if (before.isNotEmpty() && !before[0].isWhitespace()) ed.commit(" ")
                    }
                    ed.commit(s.text + " ")
                }
                Suggestion.Kind.PREDICTION -> {
                    if (composing.isNotEmpty()) commitComposing(" ", correct = false)
                    val before = ed.before(1)
                    if (before.isNotEmpty() && !before[0].isWhitespace()) ed.commit(" ")
                    val word = applyShift(s.text, wholeWord = true)
                    learn(previousWord(), word)
                    ed.commit("$word ")
                }
                else -> {
                    val word = s.text
                    // Tapping the word exactly as typed is how a new word is taught.
                    val weight = if (s.kind == Suggestion.Kind.TYPED) Suggester.KNOWN_AFTER else 1
                    val prev = previousWord()
                    composing.setLength(0)
                    fromGesture = false
                    ed.commit("$word ")
                    learn(prev, word, weight)
                }
            }
        }
        lastCorrection = null
        autoSpace = true
        lastWasSpace = false
        if (shift == Shift.ONCE && !autoShifted) shift = Shift.OFF
        refreshCaps()
        refreshStrip()
    }

    /** A swiped word, best candidate first. It is composed, not committed, until the next key. */
    fun onGesture(candidates: List<String>) {
        val ed = editor ?: return
        if (candidates.isEmpty()) return
        val shifted = candidates.map { applyShift(it, wholeWord = true) }
        spaceAfterMark = false
        ed.batch {
            if (composing.isNotEmpty()) {
                commitComposing(" ", correct = !fromGesture)
            } else {
                val b = ed.before(1)
                if (b.isNotEmpty() && (b[0].isLetterOrDigit() || b[0] in ".,!?;:")) ed.commit(" ")
            }
            composingPrevious = previousWordIn(ed.before(64))
            composing.append(shifted[0])
            ed.setComposing(composing.toString())
        }
        fromGesture = true
        gestureAlternatives = shifted
        lastCorrection = null
        autoSpace = false
        lastWasSpace = false
        if (shift == Shift.ONCE) shift = Shift.OFF
        refreshStrip()
    }

    fun onEmoji(emoji: String) {
        val ed = editor ?: return
        spaceAfterMark = false
        ed.batch {
            commitComposing(if (composing.isNotEmpty()) " " else "", correct = false)
            ed.commit(emoji)
        }
        autoSpace = false
        lastWasSpace = false
        refreshStrip()
    }

    fun onPaste(text: String) {
        val ed = editor ?: return
        spaceAfterMark = false
        ed.batch {
            commitComposing("", correct = false)
            ed.commit(text)
        }
        autoSpace = false
        lastWasSpace = false
        refreshCaps()
        refreshStrip()
    }

    fun onShift(doubleTap: Boolean) {
        shift = when (shift) {
            Shift.OFF -> Shift.ONCE
            Shift.ONCE -> if (doubleTap) Shift.LOCKED else Shift.OFF
            Shift.LOCKED -> Shift.OFF
        }
        autoShifted = false
        listener?.invoke()
    }

    /** The cursor moved somewhere the engine did not put it — a tap in the text, an arrow key. */
    fun onCursorMoved() {
        val ed = editor ?: return
        spaceAfterMark = false
        if (composing.isNotEmpty()) {
            ed.finishComposing()
            composing.setLength(0)
        }
        fromGesture = false
        lastCorrection = null
        autoSpace = false
        lastWasSpace = false
        refreshCaps()
        refreshStrip()
    }

    fun onLanguageChanged() {
        keepAsTyped = null
        refreshStrip()
    }

    // ---- internals --------------------------------------------------------

    /** Whether the space before the cursor is one a mark added; clears the mark either way. */
    private fun takeMarkSpace(ed: Editor): Boolean {
        val was = spaceAfterMark && ed.before(1) == " "
        spaceAfterMark = false
        return was
    }

    /** Prose fields only: URLs, emails, passwords and raw-key fields get exactly what was typed. */
    private fun spacesAfterMarks(): Boolean = options.spaceAfterPunctuation && policy.suggest

    private fun endsAMarkable(before: String): Boolean =
        before.isNotEmpty() && (before[0].isLetterOrDigit() || before[0] in CLOSING_BRACKETS || before[0] in "\"'’")

    private fun commitComposing(separator: String, correct: Boolean) {
        val ed = editor ?: return
        if (composing.isEmpty()) {
            if (separator.isNotEmpty()) ed.commit(separator)
            return
        }
        val typed = composing.toString()
        val correction = if (correct && typed != keepAsTyped && policy.autocorrect && options.autocorrect) {
            suggester?.forWord(typed, previousWord(), autocorrect = true, emojiOn = false)?.autocorrect
        } else {
            null
        }
        val word = correction ?: typed
        val prev = previousWord()
        ed.commit(word + separator)
        lastCorrection = if (correction != null && correction != typed) Correction(typed, word, separator) else null
        learn(prev, word, if (typed == keepAsTyped) Suggester.KNOWN_AFTER else 1)
        keepAsTyped = null
        composing.setLength(0)
        fromGesture = false
    }

    private fun learn(previous: String?, word: String, weight: Int = 1) {
        if (!policy.learn || language.isEmpty()) return
        if (!LearnedModel.isLearnable(word)) return
        learned.learn(language, previous, word, weight)
        onLearned()
    }

    /** The word before the composing one, for pairs; null across sentence punctuation. */
    private fun previousWord(): String? {
        val ed = editor ?: return null
        if (composing.isNotEmpty()) return composingPrevious
        return previousWordIn(ed.before(64))
    }

    /** After a plain backspace, pick the word at the cursor back up so suggestions reappear. */
    private fun resumeWord() {
        if (policy.suggest) adoptWordBeforeCursor()
    }

    /**
     * The word ending at the cursor becomes the composing word, in place. The text is
     * not deleted and re-set: on a phone the field reports those two edits late, the
     * late report reads as the user moving the cursor, and the word was dropped again —
     * so typing on went into a new word that knew nothing of the letters before it.
     */
    private fun adoptWordBeforeCursor() {
        val ed = editor ?: return
        if (!policy.suggest) return
        val after = ed.after(1)
        if (after.isNotEmpty() && isWordChar(after[0], true)) return
        val before = ed.before(48)
        var i = before.length
        while (i > 0 && isWordChar(before[i - 1], true)) i--
        val word = before.substring(i)
        // A word that fills the whole window may be longer than it; leave it alone.
        if (word.isEmpty() || word.length == before.length && before.length == 48) return
        if (!isWordChar(word[0], false)) return
        composingPrevious = previousWordIn(before.dropLast(word.length))
        ed.composeBefore(word)
        composing.append(word)
    }

    private fun applyShift(text: String, wholeWord: Boolean = false): String = when (shift) {
        Shift.OFF -> text
        Shift.ONCE -> if (wholeWord) text.replaceFirstChar { it.uppercaseChar() } else text.uppercase()
        Shift.LOCKED -> text.uppercase()
    }

    private fun refreshCaps() {
        if (shift == Shift.LOCKED) return
        val ed = editor
        val want = ed != null && options.autoCaps && policy.capsMode != 0 && composing.isEmpty() &&
            ed.capsMode(policy.capsMode) != 0
        if (want) {
            shift = Shift.ONCE
            autoShifted = true
        } else if (autoShifted) {
            shift = Shift.OFF
            autoShifted = false
        } else if (shift == Shift.OFF) {
            autoShifted = false
        }
    }

    fun refreshStrip() {
        val sg = suggester
        strip = when {
            !policy.suggest || !options.predictions || sg == null -> Strip.EMPTY
            fromGesture -> Strip(
                left = gestureAlternatives.getOrNull(1)?.let { Suggestion(it, Suggestion.Kind.COMPLETION) },
                center = Suggestion(composing.toString(), Suggestion.Kind.TYPED),
                right = gestureAlternatives.getOrNull(2)?.let { Suggestion(it, Suggestion.Kind.COMPLETION) },
            )
            composing.isNotEmpty() -> sg.forWord(
                composing.toString(),
                previousWord(),
                autocorrect = policy.autocorrect && options.autocorrect && composing.toString() != keepAsTyped,
                emojiOn = options.emojiPredictions,
            )
            else -> sg.predictNext(previousWord(), options.emojiPredictions)
        }
        listener?.invoke()
    }

    companion object {
        private const val CORRECTING_PUNCTUATION = ".,!?;:)]}\"'"
        private const val SWAPPING_PUNCTUATION = ".,!?;:"
        private const val CLOSING_BRACKETS = ")]}"

        fun isWordChar(c: Char, inWord: Boolean): Boolean =
            c.isLetter() || c.isDigit() || (inWord && (c == '\'' || c == '’'))

        fun previousWordIn(before: String): String? {
            var end = before.length
            while (end > 0 && before[end - 1] == ' ') end--
            if (end == 0 || !isWordChar(before[end - 1], true)) return null
            var start = end
            while (start > 0 && isWordChar(before[start - 1], true)) start--
            if (start == end) return null
            return before.substring(start, end)
        }

        /** How much a word-delete removes: trailing spaces, then a word, or else a run of symbols. */
        fun wordDeleteLength(before: String): Int {
            var i = before.length
            while (i > 0 && before[i - 1].isWhitespace()) i--
            if (i > 0 && isWordChar(before[i - 1], true)) {
                while (i > 0 && isWordChar(before[i - 1], true)) i--
            } else {
                while (i > 0 && !before[i - 1].isWhitespace() && !isWordChar(before[i - 1], true)) i--
            }
            return before.length - i
        }

        private fun endsWithWordThenSpace(s: String): Boolean =
            s.length == 2 && s[1] == ' ' && (s[0].isLetterOrDigit())
    }
}
