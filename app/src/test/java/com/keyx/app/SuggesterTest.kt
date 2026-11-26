package com.keyx.app

import com.keyx.app.predict.LearnedModel
import com.keyx.app.predict.Suggestion
import com.keyx.app.predict.Touch
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
    fun theSecondRowHoldsThreeMoreWords() {
        for (strip in listOf(
            en.forWord("th", null, autocorrect = true, emojiOn = false),
            en.predictNext(null, emojiOn = false),
        )) {
            assertEquals(3, strip.extra.size)
            val shown = strip.all.map { it.text.lowercase() }.toSet()
            val extra = strip.extra.map { it.text.lowercase() }
            assertEquals("no word twice: $extra", 3, extra.toSet().size)
            assertTrue("not already shown: $shown $extra", extra.none { it in shown })
        }
    }

    @Test
    fun missingApostrophesAreRestored() {
        assertEquals("how's", corrected("hows"))
        assertEquals("How's", corrected("Hows"))
        assertEquals("what's", corrected("whats"))
        assertEquals("where's", corrected("wheres"))
        assertEquals("who's", corrected("whos"))
        assertEquals("would've", corrected("wouldve"))
        assertEquals("can't", corrected("cant"))
        assertEquals("I've", corrected("ive"))
        // Real words in their own right stay as typed.
        for (w in listOf("its", "lets", "ill", "well", "were", "shed", "hell", "wed")) assertNull(w, corrected(w))
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

    @Test
    fun onlyNamesAreCapitalizedOnTheirOwn() {
        assertEquals("I", corrected("i"))
        assertEquals("I'm", corrected("im"))
        assertEquals("Mr", corrected("mr"))
        assertEquals("Joe", corrected("joe"))
        // The word lists also hold symbols and acronyms: typed lowercase, they stay so.
        for (w in listOf("th", "er", "sam", "mia", "ok", "le")) assertNull(w, corrected(w))
    }

    @Test
    fun aWordTypedInLowercaseIsShownOnceAndSpaceKeepsIt() {
        for (w in listOf("th", "ok", "er")) {
            val strip = en.forWord(w, null, autocorrect = true, emojiOn = false)
            val words = (strip.all + strip.extra).map { it.text }
            assertEquals("$w: $words", words.size, words.toSet().size)
            assertEquals(w, strip.center?.text)
            assertNull(strip.autocorrect)
        }
        assertEquals("the", en.forWord("th", null, autocorrect = true, emojiOn = false).left?.text)
        val ok = en.forWord("ok", null, autocorrect = true, emojiOn = false)
        assertTrue("OK is one tap away: ${ok.all + ok.extra}", (ok.all + ok.extra).any { it.text == "OK" })
    }

    private val center = Touch(0f, 0f)

    private fun touched(typed: String, at: Int, t: Touch, s: com.keyx.app.predict.Suggester = en) =
        s.forWord(typed, null, true, false, typed.indices.map { if (it == at) t else center }).autocorrect

    @Test
    fun aKnownRareWordIsASlipOnlyWhenTouchedTowardTheCommonOne() {
        // i is up and to the left of k; n is to the right of b, and j above n.
        assertEquals("is", touched("ks", 0, Touch(-0.2f, -0.45f)))
        assertEquals("not", touched("nit", 1, Touch(0.45f, 0f)))
        // Hit squarely, or with no touch to go by, they are what was typed.
        for (w in listOf("ks", "nit", "jot", "shat", "aol", "ger", "mg")) {
            // ("ger" -> "Ger" is only its case.)
            for (c in listOf(corrected(w), touched(w, 0, center))) assertTrue("$w -> $c", c == null || c.equals(w, true))
        }
        // Everyday words are safe even at the edge.
        for (w in listOf("thus", "lime", "tour", "van", "nor", "yo", "bug")) assertNull(w, touched(w, 0, Touch(0.45f, 0f)))
    }

    @Test
    fun aWordTheOperatorUsesIsNeverShadowed() {
        val learned = LearnedModel()
        learned.learn("en", null, "nit", 2)
        assertNull(touched("nit", 1, Touch(0.45f, 0f), TestData.suggester(learned = learned)))
    }

    @Test
    fun aLetterTouchedTowardTheSpaceBarSplitsTheWords() {
        val low = Touch(0f, 0.4f)
        assertEquals("this is", touched("thisnis", 4, low))
        assertEquals("This is", touched("Thisnis", 4, low))
        assertEquals("want to", touched("wantbto", 4, low))
        // Hit squarely too: between two everyday words a letter over the space bar is the space.
        assertEquals("want to", touched("wantbto", 4, center))
        assertEquals("going to", touched("goingnto", 5, center))
        assertEquals("this is", touched("thisvis", 4, center))
        // A one-letter half needs the low touch; squarely hit, it is only offered.
        assertTrue(touched("inborn", 1, center).let { it == null || ' ' !in it })
        // Touched high, it was the letter.
        assertNull(touched("wantbto", 4, Touch(0f, -0.4f)))
    }

    @Test
    fun aSplitIntoARarerWordIsOfferedNotForced() {
        val strip = TestData.suggester().forWord("inbboston", null, autocorrect = true, emojiOn = false)
        assertTrue(strip.all.any { it.text == "in Boston" })
        assertTrue(strip.autocorrect.let { it == null || ' ' !in it })
    }

    @Test
    fun commonWordsRunTogetherAreTakenApartButRealWordsAreNot() {
        assertEquals("of the", corrected("ofthe"))
        assertEquals("in the", corrected("inthe"))
        for (w in listOf("trelliscards", "windburn", "inpatient", "sidewall", "schoolbook", "overbite", "mybank", "bookends")) {
            val c = corrected(w)
            assertTrue("$w -> $c", c == null || ' ' !in c)
        }
        val de = TestData.suggester("de_DE")
        for (w in listOf("frauenhaus", "kindergeld", "sonnenschein", "haustür")) {
            val c = corrected(w, de)
            assertTrue("$w -> $c", c == null || ' ' !in c)
        }
    }

    @Test
    fun whereTheKeyWasTouchedMakesANearMissCheap() {
        // r and g each hit right on their edge toward t and h: two near misses, not two slips.
        val edges = listOf(Touch(0.45f, 0f), Touch(0.45f, 0f), center)
        assertEquals("the", en.forWord("rge", null, autocorrect = true, emojiOn = false, touches = edges).autocorrect)
        // The same letters hit dead center are what was typed.
        val middles = listOf(center, center, center)
        assertTrue(en.forWord("rge", null, autocorrect = true, emojiOn = false, touches = middles).autocorrect != "the")
    }

    @Test
    fun aWordCutByAStraySpaceIsRejoinedButRealPairsAreNot() {
        assertEquals("have", en.rejoin("hav", "e", false))
        assertEquals("because", en.rejoin("becaus", "e", false))
        assertEquals("There", en.rejoin("Ther", "e", false))
        // Space hit for the v above it, only when touched high.
        assertEquals("believe", en.rejoin("belie", "e", true))
        assertNull(en.rejoin("belie", "e", false))
        for ((l, r) in listOf("me" to "n", "plan" to "b", "wind" to "burn", "an" to "other", "to" to "day", "with" to "in")) {
            assertNull("$l $r", en.rejoin(l, r, true))
        }
    }

    @Test
    fun bundledPairsGiveContextBeforeAnythingIsLearned() {
        assertTrue(en.prior("much", "very") > en.prior("much", null))
        val next = en.predictNext("thank", emojiOn = false)
        assertTrue("$next", next.all.any { it.text.equals("you", true) })
    }
}
