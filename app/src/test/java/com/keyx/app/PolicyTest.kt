package com.keyx.app

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.keyx.app.ime.FieldPolicy
import com.keyx.app.ime.SpaceGesture
import com.keyx.app.ime.SpaceGesture.Result
import com.keyx.app.layout.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyTest {
    private val text = InputType.TYPE_CLASS_TEXT

    @Test
    fun passwordsAreNeverLearnedOrSuggested() {
        for (v in listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )) {
            val p = FieldPolicy.from(text or v, 0)
            assertFalse(p.learn); assertFalse(p.suggest); assertFalse(p.autocorrect); assertTrue(p.password)
            assertEquals(0, p.capsMode)
        }
        val pin = FieldPolicy.from(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, 0)
        assertFalse(pin.learn); assertEquals(Page.NUMBERS, pin.page)
    }

    @Test
    fun noPersonalizedLearningIsHonoured() {
        val p = FieldPolicy.from(text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertFalse(p.learn)
        assertTrue(p.suggest)
    }

    @Test
    fun machineTextIsNotCorrected() {
        val p = FieldPolicy.from(text or InputType.TYPE_TEXT_VARIATION_URI, 0)
        assertFalse(p.autocorrect); assertFalse(p.learn); assertEquals(0, p.capsMode)
        assertFalse(FieldPolicy.from(text or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 0).autocorrect)
    }

    @Test
    fun numberFieldsGetTheNumberPad() {
        assertEquals(Page.NUMBERS, FieldPolicy.from(InputType.TYPE_CLASS_PHONE, 0).page)
        assertEquals(Page.LETTERS, FieldPolicy.from(text, 0).page)
    }

    @Test
    fun enterFollowsTheFieldAction() {
        assertEquals(EditorInfo.IME_ACTION_SEARCH, FieldPolicy.from(text, EditorInfo.IME_ACTION_SEARCH).enterAction)
        assertEquals(null, FieldPolicy.from(text or InputType.TYPE_TEXT_FLAG_MULTI_LINE, EditorInfo.IME_ACTION_SEND).enterAction)
        assertEquals(null, FieldPolicy.from(text, EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_ENTER_ACTION).enterAction)
    }

    @Test
    fun spacebarFlickSwitchesLanguageAndTapIsASpace() {
        val kw = 100f
        assertEquals(Result.SPACE, SpaceGesture.onRelease(5f, 3f, 90, false, kw, 2))
        assertEquals(Result.LANGUAGE_NEXT, SpaceGesture.onRelease(180f, 10f, 200, false, kw, 2))
        assertEquals(Result.LANGUAGE_PREVIOUS, SpaceGesture.onRelease(-180f, 10f, 200, false, kw, 2))
        // One language: a flick does nothing rather than typing a space.
        assertEquals(Result.NONE, SpaceGesture.onRelease(180f, 10f, 200, false, kw, 1))
        // A slow drag is not a flick; after cursor control it is nothing at all.
        assertEquals(Result.NONE, SpaceGesture.onRelease(180f, 10f, 900, false, kw, 2))
        assertEquals(Result.NONE, SpaceGesture.onRelease(5f, 0f, 900, true, kw, 2))
    }

    @Test
    fun cursorControlStartsOnlyOnAStillLongPressAndSteps() {
        assertTrue(SpaceGesture.startsCursor(10f, 5f, 100f))
        assertFalse(SpaceGesture.startsCursor(80f, 5f, 100f))
        assertEquals(3, SpaceGesture.cursorSteps(100f, 100f))
        assertEquals(-3, SpaceGesture.cursorSteps(-100f, 100f))
    }
}
