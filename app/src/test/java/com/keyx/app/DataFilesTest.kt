package com.keyx.app

import com.keyx.app.layout.BuildOptions
import com.keyx.app.layout.KeyType
import com.keyx.app.layout.KeyboardBuilder
import com.keyx.app.layout.LanguageLayout
import com.keyx.app.layout.LayoutException
import com.keyx.app.layout.Page
import com.keyx.app.layout.SymbolSet
import com.keyx.app.theme.KeyboardTheme
import com.keyx.app.theme.ThemeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.graphics.luminance
import org.junit.Test
import java.io.File

/** Every layout and theme that ships parses, and builds rows that add up. */
class DataFilesTest {
    private val symbols = SymbolSet.parse(TestData.text("layouts/symbols.json"))
    private val languages = listOf("en_US", "en_GB", "de_DE", "ru_RU")

    @Test
    fun everyLayoutBuildsEveryPageWithEvenRowWidths() {
        for (id in languages) {
            val layout = TestData.layout(id)
            assertTrue(File("src/main/assets/dicts/${layout.dictionary}.tsv").exists())
            for (page in Page.values()) {
                for (o in listOf(BuildOptions(), BuildOptions(numberRow = false, emojiKey = false))) {
                    val rows = KeyboardBuilder.build(page, layout, symbols, o)
                    val widths = rows.map { r -> r.keys.sumOf { it.weight.toDouble() } }
                    widths.forEach { w -> assertEquals("$id $page $widths", widths[0], w, 0.01) }
                    assertEquals(1, rows.flatMap { it.keys }.count { it.type == KeyType.ENTER })
                    assertEquals(1, rows.flatMap { it.keys }.count { it.type == KeyType.SPACE })
                }
            }
        }
    }

    @Test
    fun letterPageMatchesTheSwiftKeyShape() {
        val rows = KeyboardBuilder.build(Page.LETTERS, TestData.layout("en_US"), symbols, BuildOptions())
        assertEquals(5, rows.size) // number row + 3 letter rows + bottom
        assertEquals("1234567890", rows[0].keys.joinToString("") { it.text })
        val a = rows[2].keys.first { it.text == "a" }
        assertEquals("@", a.hint)
        assertEquals("@", a.popup.first())
        assertTrue("all accents", a.popup.containsAll(listOf("à", "á", "ä")))
        val e = rows[1].keys.first { it.text == "e" }
        assertEquals(null, e.hint) // the number row already shows 3
        assertEquals("3", e.popup.first())
        val noRow = KeyboardBuilder.build(Page.LETTERS, TestData.layout("en_US"), symbols, BuildOptions(numberRow = false))
        assertEquals("3", noRow[0].keys.first { it.text == "e" }.hint)
        assertEquals(KeyType.SHIFT, rows[3].keys.first { it.type != KeyType.SPACER }.type)
        assertEquals(
            listOf(KeyType.SYMBOLS, KeyType.EMOJI, KeyType.COMMA, KeyType.SPACE, KeyType.PERIOD, KeyType.ENTER),
            rows[4].keys.map { it.type },
        )
        // Long-press period opens on "." with "?" one to the right and "!" one to the left.
        val period = rows[4].keys.first { it.type == KeyType.PERIOD }
        assertEquals(".", period.popup[period.popupAnchor])
        assertEquals("?", period.popup[period.popupAnchor + 1])
        assertEquals("!", period.popup[period.popupAnchor - 1])
    }

    @Test
    fun badLayoutsSayWhatIsWrong() {
        val e = runCatching { LanguageLayout.parse("""{"id":"x","name":"X","dictionary":"en"}""") }.exceptionOrNull()
        assertTrue(e is LayoutException && e.message!!.contains("rows"))
        val bad = runCatching { LanguageLayout.parse("{") }.exceptionOrNull()
        assertTrue(bad is LayoutException)
    }

    @Test
    fun layoutsAcceptListRowsForMultiCharacterKeys() {
        val l = LanguageLayout.parse("""{"id":"x","name":"X","dictionary":"en","rows":[["a","b"],"cd"]}""")
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), l.rows)
    }

    @Test
    fun everyThemeParsesAndPhosphorIsTheDefault() {
        val ids = File("src/main/assets/themes").list()!!.map { it.removeSuffix(".json") }
        assertTrue(KeyboardTheme.DEFAULT_ID in ids)
        for (id in ids) assertEquals(id, KeyboardTheme.parse(TestData.text("themes/$id.json")).id)
        val p = KeyboardTheme.parse(TestData.text("themes/phosphor_green.json"))
        assertEquals(0xFF001700.toInt(), p.keyboardBackground)
        assertEquals(0xFF00FF0F.toInt(), p.keyText)
    }

    @Test
    fun everyThemeGivesTheSettingsAppAReadableScheme() {
        val ids = File("src/main/assets/themes").list()!!.map { it.removeSuffix(".json") }
        assertTrue(ids.containsAll(listOf("ocean", "futuristic", "synthwave", "sticky_notes", "blueprint")))
        for (id in ids) {
            val scheme = com.keyx.app.ui.theme.colorSchemeFor(KeyboardTheme.parse(TestData.text("themes/$id.json")))
            val bg = scheme.background.luminance()
            val fg = scheme.onBackground.luminance()
            val contrast = (maxOf(bg, fg) + 0.05) / (minOf(bg, fg) + 0.05)
            assertTrue("$id text contrast $contrast", contrast >= 4.5)
        }
        val sticky = KeyboardTheme.parse(TestData.text("themes/sticky_notes.json"))
        assertTrue(com.keyx.app.ui.theme.colorSchemeFor(sticky).background.luminance() > 0.5f)
    }

    @Test
    fun themeColorsAreValidated() {
        assertEquals(0x8000FF00.toInt(), KeyboardTheme.parseColor("#8000FF00"))
        assertEquals(null, KeyboardTheme.parseColor("#12345"))
        val e = runCatching { KeyboardTheme.parse("""{"id":"t","keyText":"green"}""") }.exceptionOrNull()
        assertTrue(e is ThemeException)
    }

    @Test
    fun glowThemesCarryTheirOutlinesAndFlatThemesDoNot() {
        val neon = KeyboardTheme.parse(TestData.text("themes/neon.json"))
        assertEquals(0.6f, neon.glow)
        assertEquals(0xFF5B4FB0.toInt(), neon.keyEdge)
        assertEquals(0xFF5B4FB0.toInt(), neon.functionBorder)
        assertTrue(KeyboardTheme.parse(TestData.text("themes/white_glow.json")).glow > 0f)
        val flat = KeyboardTheme.parse(TestData.text("themes/phosphor_green.json"))
        assertEquals(0f, flat.glow)
        assertEquals(null, flat.keyEdge)
        assertEquals(null, flat.functionBorder)
        val base = TestData.text("themes/neon.json").trimEnd().removeSuffix("}")
        for (bad in listOf("1.5", "\"bright\"")) {
            val e = runCatching { KeyboardTheme.parse("$base, \"glow\": $bad}".replace("\"glow\": 0.6,", "")) }.exceptionOrNull()
            assertTrue("glow $bad", e is ThemeException)
        }
    }

    @Test
    fun emojiDataIsThere() {
        val lines = File("src/main/assets/emoji/emoji.tsv").readLines()
        assertTrue(lines.size > 1000)
        assertEquals("🍕", TestData.emoji.forWord("Pizza"))
    }
}
