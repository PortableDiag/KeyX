package com.keyx.app.layout

enum class KeyType {
    CHAR, SHIFT, BACKSPACE, ENTER, SPACE, COMMA, PERIOD,
    SYMBOLS, SYMBOLS_MORE, ALPHA, EMOJI, SPACER,
}

/**
 * A key as the view draws it. [weight] is its width in key units; [popup] is
 * what a long-press offers, left to right. The entry at [popupAnchor] opens
 * directly over the key and is preselected, so the others sit where a slide
 * left or right expects them.
 */
data class Key(
    val type: KeyType,
    val text: String = "",
    val hint: String? = null,
    val popup: List<String> = emptyList(),
    val weight: Float = 1f,
    val popupAnchor: Int = 0,
)

data class KeyRow(val keys: List<Key>, val height: Float = 1f)

enum class Page { LETTERS, SYMBOLS, SYMBOLS_MORE, NUMBERS }

data class BuildOptions(
    val numberRow: Boolean = true,
    val longPressSymbols: Boolean = true,
    val allAccents: Boolean = true,
    val emojiKey: Boolean = true,
    val voiceKey: Boolean = true,
)

/** Turns a layout plus the operator's options into rows of keys. */
object KeyboardBuilder {
    const val NUMBER_ROW_HEIGHT = 0.8f

    fun build(page: Page, layout: LanguageLayout, symbols: SymbolSet, o: BuildOptions): List<KeyRow> =
        when (page) {
            Page.LETTERS -> letters(layout, symbols, o)
            Page.SYMBOLS -> symbolPage(symbols.pages.getValue("symbols"), symbols, o, KeyType.SYMBOLS_MORE, "=\\<")
            Page.SYMBOLS_MORE -> symbolPage(symbols.pages.getValue("symbols2"), symbols, o, KeyType.SYMBOLS, "?123")
            Page.NUMBERS -> numbers(symbols, o)
        }

    private fun letters(layout: LanguageLayout, symbols: SymbolSet, o: BuildOptions): List<KeyRow> {
        val width = layout.rows.maxOf { it.size }.coerceAtLeast(10).toFloat()
        val rows = ArrayList<KeyRow>()
        if (o.numberRow) {
            rows += KeyRow(layout.numberRow.map { charKey(it, null, symbols) }.pad(width), NUMBER_ROW_HEIGHT)
        }
        layout.rows.forEachIndexed { i, row ->
            val keys = row.map { k ->
                val symbol = if (o.longPressSymbols) layout.hints[k] else null
                // With the number row showing, a digit hint on the top row says nothing new;
                // the digit stays on long-press.
                val hint = symbol.takeUnless { o.numberRow && it in layout.numberRow }
                val accents = layout.accents[k]?.let { LanguageLayout.codePoints(it) }.orEmpty()
                val shown = if (o.allAccents) accents else accents.take(1)
                Key(KeyType.CHAR, k, hint, listOfNotNull(symbol) + shown)
            }
            rows += if (i == layout.rows.lastIndex) {
                val side = ((width - keys.size) / 2f).coerceIn(1f, 1.5f)
                KeyRow((listOf(Key(KeyType.SHIFT, weight = side)) + keys + Key(KeyType.BACKSPACE, weight = side)).pad(width))
            } else {
                KeyRow(keys.pad(width))
            }
        }
        rows += bottomRow(width, o, symbols, Key(KeyType.SYMBOLS, "123", weight = 1.5f))
        return rows
    }

    private fun symbolPage(
        page: List<List<String>>,
        symbols: SymbolSet,
        o: BuildOptions,
        toggle: KeyType,
        toggleLabel: String,
    ): List<KeyRow> {
        val width = page.maxOf { it.size }.coerceAtLeast(10).toFloat()
        val rows = page.mapIndexed { i, row ->
            val keys = row.map { charKey(it, null, symbols) }
            if (i == page.lastIndex) {
                val side = ((width - keys.size) / 2f).coerceIn(1f, 1.5f)
                KeyRow((listOf(Key(toggle, toggleLabel, weight = side)) + keys + Key(KeyType.BACKSPACE, weight = side)).pad(width))
            } else {
                KeyRow(keys.pad(width))
            }
        }
        return rows + bottomRow(width, o, symbols, Key(KeyType.ALPHA, "ABC", weight = 1.5f))
    }

    private fun numbers(symbols: SymbolSet, o: BuildOptions): List<KeyRow> {
        val page = symbols.pages.getValue("numbers")
        val rows = page.mapIndexed { i, row ->
            val keys = row.map { charKey(it, null, symbols) }
            KeyRow(if (i == page.lastIndex) keys + Key(KeyType.BACKSPACE) else keys)
        }
        val width = rows.maxOf { r -> r.keys.sumOf { it.weight.toDouble() } }.toFloat()
        return rows.map { KeyRow(it.keys.pad(width), it.height) } + KeyRow(
            listOf(
                Key(KeyType.ALPHA, "ABC"),
                charKey("0", null, symbols),
                Key(KeyType.SPACE, "", weight = 1f),
                Key(KeyType.ENTER),
            ).pad(width),
        )
    }

    private fun bottomRow(width: Float, o: BuildOptions, symbols: SymbolSet, pageKey: Key): KeyRow {
        val keys = ArrayList<Key>()
        keys += pageKey
        if (o.emojiKey) keys += Key(KeyType.EMOJI)
        keys += Key(
            KeyType.COMMA, ",",
            hint = if (o.voiceKey) "mic" else null,
            popup = LanguageLayout.codePoints(symbols.longPress[","].orEmpty()),
        )
        val periodPopup = LanguageLayout.codePoints(symbols.longPress["."].orEmpty())
        val period = Key(
            KeyType.PERIOD, ".",
            hint = symbols.periodHint,
            popup = periodPopup,
            popupAnchor = periodPopup.indexOf(".").coerceAtLeast(0),
        )
        val enter = Key(KeyType.ENTER, weight = 1.5f)
        val used = keys.sumOf { it.weight.toDouble() }.toFloat() + period.weight + enter.weight
        keys += Key(KeyType.SPACE, weight = (width - used).coerceAtLeast(2f))
        keys += period
        keys += enter
        return KeyRow(keys)
    }

    private fun charKey(k: String, hint: String?, symbols: SymbolSet) =
        Key(KeyType.CHAR, k, hint, LanguageLayout.codePoints(symbols.longPress[k].orEmpty()))

    /** Centers a short row with half-width spacers, as the middle row of QWERTY is. */
    private fun List<Key>.pad(width: Float): List<Key> {
        val used = sumOf { it.weight.toDouble() }.toFloat()
        if (used >= width - 0.01f) return this
        val side = Key(KeyType.SPACER, weight = (width - used) / 2f)
        return listOf(side) + this + side
    }
}
