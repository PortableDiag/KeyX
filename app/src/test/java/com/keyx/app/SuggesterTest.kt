package com.keyx.app

import com.keyx.app.predict.LearnedModel
import com.keyx.app.predict.Suggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggesterTest {
    private val en = TestData.suggester()

    private fun corrected(typed: String, s: com.keyx.app.predict.Suggester = en) =
        s.forWord(typed, null, autocorrect = true, emojiOn = false).autocorrect

    @Test
    fun commonTyposAreCorrected() {
        assertEquals("the", corrected("teh"))
        assertEquals("hello", corrected("helo"))
        assertEquals("receive", corrected("recieve"))
        assertEquals("don't", corrected("dont"))
        assertEquals("I'm", corrected("im"))
        assertEquals("the", corrected("tge")) // g is next to h
    }

    @Test
    fun realWordsAreNeverCorrected() {
        assertNull(corrected("form"))
        assertNull(corrected("from"))
        assertNull(corrected("hello"))
    }

    @Test
    fun typedCaseCarriesOver() {
        assertEquals("Hello", corrected("Helo"))
        assertNull(corrected("HELO")) // all caps is deliberate
    }

    @Test
    fun wordsTheOperatorUsesStopBeingCorrected() {
        val learned = LearnedModel()
        val s = TestData.suggester(learned = learned)
        assertEquals("hello", corrected("helo", s))
        learned.learn("en", null, "helo", 2)
        assertNull(corrected("helo", s))
    }

    @Test
    fun theStripShowsTheTypedWordLeftWhenCorrecting() {
        val strip = en.forWord("helo", null, autocorrect = true, emojiOn = false)
        assertEquals(Suggestion("hello", Suggestion.Kind.CORRECTION), strip.center)
        assertEquals(Suggestion("helo", Suggestion.Kind.TYPED), strip.left)
    }

    @Test
    fun completionsFillTheSidesWhenTheWordIsFine() {
        val strip = en.forWord("tha", null, autocorrect = true, emojiOn = false)
        val words = strip.all.map { it.text }
        assertTrue(words.toString(), "that" in words || "thanks" in words || "than" in words)
    }

    @Test
    fun pairsTheOperatorTypesArePredicted() {
        val learned = LearnedModel()
        val s = TestData.suggester(learned = learned)
        repeat(3) { learned.learn("en", "see", "you") }
        assertEquals("you", s.predictNext("see", emojiOn = false).center?.text)
    }

    @Test
    fun ukAndUsEnglishSpellDifferentlyButLearnTogether() {
        val learned = LearnedModel()
        val gb = TestData.suggester("en_GB", learned)
        assertEquals("favourite", corrected("favourtie", gb))
        assertNull(corrected("colour", gb))
        assertEquals("favorite", corrected("favortie"))
        learned.learn("en", null, "KeyX", 3)
        assertNull(corrected("KeyX", TestData.suggester("en_US", learned)))
        assertNull(corrected("KeyX", gb))
    }

    @Test
    fun germanAccentsAndNounsComeBack() {
        val de = TestData.suggester("de_DE")
        assertEquals("mädchen", corrected("madchen", de)?.lowercase())
        assertEquals("für", corrected("fur", de))
    }

    @Test
    fun russianTranspositionIsCorrected() {
        val ru = TestData.suggester("ru_RU")
        assertEquals("привет", corrected("пирвет", ru))
    }

    @Test
    fun aFullStripIsFastEnoughToRunPerKeystroke() {
        en.forWord("warm", null, true, true) // warm-up
        val t0 = System.nanoTime()
        for (w in listOf("recieve", "definately", "tommorow", "beleive", "wierd")) en.forWord(w, "the", true, true)
        val perWordMs = (System.nanoTime() - t0) / 5 / 1_000_000
        // ~1.5 ms on a desktop JVM. A phone is a few times slower, and this runs on
        // the UI thread for every keystroke: 20 ms here would already be felt.
        assertTrue("took ${perWordMs}ms per word", perWordMs < 20)
    }
}
