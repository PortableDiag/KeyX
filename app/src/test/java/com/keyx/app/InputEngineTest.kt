package com.keyx.app

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.keyx.app.ime.FieldPolicy
import com.keyx.app.ime.InputEngine
import com.keyx.app.ime.Shift
import com.keyx.app.predict.LearnedModel
import com.keyx.app.predict.Suggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InputEngineTest {
    private lateinit var learned: LearnedModel
    private lateinit var ed: FakeEditor
    private lateinit var engine: InputEngine

    private val chat = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

    @Before
    fun setUp() {
        learned = LearnedModel()
        ed = FakeEditor()
        engine = InputEngine(learned)
        engine.editor = ed
        engine.suggester = TestData.suggester(learned = learned)
        engine.startInput(FieldPolicy.from(chat, 0))
    }

    private fun type(s: String) = s.forEach { c ->
        when (c) {
            ' ' -> engine.onSpace()
            else -> engine.onText(c.toString())
        }
    }

    @Test
    fun startOfFieldIsCapitalizedAndShiftReleasesAfterOneLetter() {
        assertEquals(Shift.ONCE, engine.shift)
        type("hi there")
        assertEquals("Hi there", ed.toString())
    }

    @Test
    fun spaceAutocorrectsAndBackspaceRevertsAndKeepsIt() {
        type("so helo ")
        assertEquals("So hello ", ed.toString())
        engine.onBackspace()
        assertEquals("So helo", ed.toString())
        assertEquals("helo", ed.composing)
        engine.onSpace()
        assertEquals("So helo ", ed.toString())
        // Reverting a correction teaches the word: next time it stays.
        type("helo ")
        assertEquals("So helo helo ", ed.toString())
    }

    @Test
    fun lowercaseIBecomesI() {
        type("so i ")
        assertEquals("So I ", ed.toString())
    }

    @Test
    fun missingApostropheIsRestored() {
        type("so dont ")
        assertEquals("So don't ", ed.toString())
    }

    @Test
    fun punctuationSwapsWithTheAutoSpace() {
        type("hi ")
        engine.onText(".")
        assertEquals("Hi. ", ed.toString())
        assertEquals(Shift.ONCE, engine.shift)
    }

    @Test
    fun doubleSpaceIsAPeriod() {
        type("hi  ")
        assertEquals("Hi. ", ed.toString())
    }

    @Test
    fun passwordFieldsLearnNothingAndCorrectNothing() {
        engine.startInput(FieldPolicy.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0))
        type("helo secretword ")
        assertEquals("helo secretword ", ed.toString())
        assertTrue(learned.isEmpty)
        assertEquals(emptyList<Suggestion>(), engine.strip.all)
    }

    @Test
    fun incognitoFieldsSuggestButLearnNothing() {
        engine.startInput(FieldPolicy.from(chat, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        type("so helo ")
        assertEquals("So hello ", ed.toString())
        assertTrue(learned.isEmpty)
    }

    @Test
    fun ordinaryTypingIsLearnedAsWordsAndPairs() {
        type("good morning ")
        assertEquals(1, learned.count("en", "morning"))
        assertEquals(1, learned.pair("en", "good", "morning"))
        type("good ")
        assertEquals("morning", engine.strip.center?.text)
    }

    @Test
    fun swipedWordsGetSpacesAndBackspaceRemovesTheWholeWord() {
        engine.onGesture(listOf("hello", "hells"))
        engine.onGesture(listOf("world"))
        assertEquals("Hello world", ed.toString())
        engine.onBackspace()
        assertEquals("Hello ", ed.toString())
        engine.onGesture(listOf("there"))
        engine.onText(".")
        assertEquals("Hello there.", ed.toString())
    }

    @Test
    fun pickingASwipeAlternativeReplacesTheSwipedWord() {
        engine.onGesture(listOf("hello", "hells", "jello"))
        engine.onPick(engine.strip.left!!)
        assertEquals("Hells ", ed.toString())
    }

    @Test
    fun deleteWordTakesTheWordAndTrailingSpaces() {
        type("one two ")
        engine.onDeleteWord()
        assertEquals("One ", ed.toString())
    }

    @Test
    fun backspaceIntoAWordResumesComposingIt() {
        type("one two ")
        engine.onBackspace()
        assertEquals("two", ed.composing)
        engine.onBackspace()
        assertEquals("One tw", ed.toString())
    }

    @Test
    fun tappingAPredictionCommitsItWithASpace() {
        type("hi ")
        val p = engine.strip.center!!
        assertEquals(Suggestion.Kind.PREDICTION, p.kind)
        engine.onPick(p)
        assertEquals("Hi ${p.text} ", ed.toString())
    }

    @Test
    fun emojiPredictionFollowsTheWord() {
        type("pizza")
        val e = engine.strip.right!!
        assertEquals(Suggestion.Kind.EMOJI, e.kind)
        engine.onPick(e)
        assertEquals("Pizza 🍕 ", ed.toString())
    }

    @Test
    fun tappingTheTypedWordTeachesIt() {
        type("so keyx")
        val typed = engine.strip.left!!
        assertEquals(Suggestion.Kind.TYPED, typed.kind)
        engine.onPick(typed)
        type("keyx ")
        assertEquals("So keyx keyx ", ed.toString())
    }

    @Test
    fun enterRunsTheFieldActionOrInsertsANewline() {
        engine.startInput(FieldPolicy.from(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEND))
        type("hi")
        engine.onEnter()
        assertEquals(listOf(EditorInfo.IME_ACTION_SEND), ed.actions)
        engine.startInput(FieldPolicy.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, EditorInfo.IME_ACTION_SEND))
        engine.onEnter()
        assertTrue(ed.toString().endsWith("\n"))
    }

    @Test
    fun capsLockHoldsAndDoubleTapLocks() {
        engine.onShift(doubleTap = false) // auto ONCE -> OFF
        assertEquals(Shift.OFF, engine.shift)
        engine.onShift(doubleTap = false)
        engine.onShift(doubleTap = true)
        assertEquals(Shift.LOCKED, engine.shift)
        type("abc")
        assertEquals("ABC", ed.toString())
    }

    @Test
    fun acronymsInCapsAreLeftAlone() {
        engine.onShift(false); engine.onShift(false); engine.onShift(true)
        type("NASDQ ")
        assertEquals("NASDQ ", ed.toString())
    }
}
