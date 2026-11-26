package com.keyx.app

import com.keyx.app.predict.EmojiSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmojiSearchTest {
    private val search by lazy {
        val rows = File("src/main/assets/emoji/emoji.tsv").readLines().map { it.split('\t') }
        EmojiSearch(rows.map { it[1] to it[2] }, TestData.emoji.map)
    }

    @Test
    fun aPartWordFindsItsEmojiFirst() {
        assertEquals("🍕", search.search("pizz").first())
        assertEquals("🍕", search.search("Pizza").first())
    }

    @Test
    fun keywordsFindEmojiTheirNamesDoNot() {
        assertEquals("😂", search.search("lol").first())
        assertTrue("🎉" in search.search("party").take(3))
    }

    @Test
    fun everyWordOfTheQueryHasToMatch() {
        val found = search.search("red heart")
        assertEquals("❤️", found.first())
        assertTrue(found.none { it == "💙" })
        assertTrue(search.search("re heart").isEmpty())
    }

    @Test
    fun aWholeWordBeatsALongerOne() {
        assertEquals(listOf("🐈", "🐱"), search.search("cat").take(2))
        assertTrue(search.search("dog").take(2).contains("🐶"))
    }

    @Test
    fun nothingForNonsenseOrBlank() {
        assertTrue(search.search("zzqx").isEmpty())
        assertTrue(search.search("  ").isEmpty())
    }
}
