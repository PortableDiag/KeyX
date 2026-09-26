package com.keyx.app

import com.keyx.app.data.Sealer
import com.keyx.app.predict.LearnedModel
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator

class LearnedModelTest {
    @Test
    fun learnsWordsAndPairsPerLanguage() {
        val m = LearnedModel()
        m.learn("en", "good", "morning")
        m.learn("en", "good", "morning")
        m.learn("de", "guten", "Morgen")
        assertEquals(2, m.count("en", "morning"))
        assertEquals(0, m.count("en", "Morgen"))
        assertEquals(listOf("morning"), m.next("en", "Good", 3))
    }

    @Test
    fun roundTripsThroughJson() {
        val m = LearnedModel()
        m.learn("en", "good", "morning", 3)
        m.learn("ru", null, "привет")
        val back = LearnedModel.fromJson(JSONObject(m.toJson().toString()))
        assertEquals(3, back.count("en", "morning"))
        assertEquals(3, back.pair("en", "good", "morning"))
        assertEquals(1, back.count("ru", "привет"))
    }

    @Test
    fun importsAPlainWordListAndExportsIt() {
        val m = LearnedModel()
        val n = m.importWords("en", "# my words\nKeyX\nTrellis\t12\n\nnot a word?!\nClipX, 3\n")
        assertEquals(3, n)
        assertEquals(LearnedModel.IMPORTED_WEIGHT, m.count("en", "KeyX"))
        assertEquals(12, m.count("en", "Trellis"))
        assertEquals("Trellis\t12\nKeyX\t5\nClipX\t3\n", m.exportWords("en"))
    }

    @Test
    fun resetLeavesNothing() {
        val m = LearnedModel()
        m.learn("en", "a", "b")
        m.clear()
        assertTrue(m.isEmpty)
        assertEquals("{\"languages\":{},\"version\":1}", m.toJson().toString())
    }

    @Test
    fun onlyWordsAreLearnable() {
        assertTrue(LearnedModel.isLearnable("don't"))
        assertFalse(LearnedModel.isLearnable("1234"))
        assertFalse(LearnedModel.isLearnable("a b"))
        assertFalse(LearnedModel.isLearnable("http://x"))
    }

    @Test
    fun sealedStateOpensOnlyWithItsKeyAndIsNotPlaintext() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val sealer = Sealer { key }
        val plain = "{\"words\":{\"secret\":1}}".toByteArray()
        val sealed = sealer.seal(plain)
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("secret"))
        assertArrayEquals(plain, sealer.open(sealed))

        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        try {
            sealer.open(sealed)
            throw AssertionError("tampered data opened")
        } catch (_: AEADBadTagException) {
        }
    }
}
