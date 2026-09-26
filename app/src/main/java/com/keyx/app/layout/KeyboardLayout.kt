package com.keyx.app.layout

import org.json.JSONArray
import org.json.JSONObject

/**
 * One language's letter layout, read from assets/layouts/<id>.json or from the
 * operator's edited copy. Rows, the number row, the long-press hint on each key
 * and its accent set are all data; nothing about a language is in code.
 */
data class LanguageLayout(
    val id: String,
    val name: String,
    /** What the space bar shows, so the current language is always visible. */
    val spacebar: String,
    /** Which dictionary pack (assets/dicts/<dictionary>.tsv) predicts for it. */
    val dictionary: String,
    /**
     * Whose learned words it uses. US and UK English spell differently but are
     * typed by the same person, so both learn into "en".
     */
    val learning: String,
    val numberRow: List<String>,
    val rows: List<List<String>>,
    val hints: Map<String, String>,
    val accents: Map<String, String>,
) {
    companion object {
        /** Throws [LayoutException] with a readable message — the layout editor shows it. */
        fun parse(text: String): LanguageLayout {
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw LayoutException("Not valid JSON: ${e.message}")
            }
            fun req(name: String): String =
                json.optString(name).ifBlank { throw LayoutException("\"$name\" is missing") }
            val rows = keyRows(json.opt("rows") ?: throw LayoutException("\"rows\" is missing"))
            if (rows.size !in 1..5) throw LayoutException("\"rows\" must have 1 to 5 rows")
            if (rows.any { it.isEmpty() }) throw LayoutException("a row is empty")
            if (rows.any { it.size > 14 }) throw LayoutException("a row has more than 14 keys")
            val numberRow = json.opt("numberRow")?.let { keys(it) } ?: (1..9).map { "$it" } + "0"
            return LanguageLayout(
                id = req("id"),
                name = req("name"),
                spacebar = json.optString("spacebar").ifBlank { req("name") },
                dictionary = req("dictionary"),
                learning = json.optString("learning").ifBlank { req("dictionary") },
                numberRow = numberRow,
                rows = rows,
                hints = strings(json.optJSONObject("hints")),
                accents = strings(json.optJSONObject("accents")),
            )
        }

        private fun keyRows(value: Any): List<List<String>> {
            val arr = value as? JSONArray ?: throw LayoutException("\"rows\" must be a list")
            return (0 until arr.length()).map { keys(arr.get(it)) }
        }

        /** A row is either a string of one-character keys or a list of key labels. */
        internal fun keys(value: Any): List<String> = when (value) {
            is String -> codePoints(value)
            is JSONArray -> (0 until value.length()).map { value.getString(it) }
            else -> throw LayoutException("a row must be a string or a list")
        }

        internal fun strings(obj: JSONObject?): Map<String, String> {
            if (obj == null) return emptyMap()
            return obj.keys().asSequence().associateWith { obj.getString(it) }
        }

        fun codePoints(s: String): List<String> {
            val out = ArrayList<String>()
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                out += String(Character.toChars(cp))
                i += Character.charCount(cp)
            }
            return out
        }
    }
}

/** Symbol pages and the long-press sets for symbols, from assets/layouts/symbols.json. */
data class SymbolSet(
    val pages: Map<String, List<List<String>>>,
    val longPress: Map<String, String>,
    val periodHint: String,
) {
    companion object {
        fun parse(text: String): SymbolSet {
            val json = JSONObject(text)
            val pages = json.getJSONArray("pages")
            val map = (0 until pages.length()).associate { i ->
                val p = pages.getJSONObject(i)
                val rows = p.getJSONArray("rows")
                p.getString("id") to (0 until rows.length()).map { LanguageLayout.keys(rows.get(it)) }
            }
            return SymbolSet(map, LanguageLayout.strings(json.optJSONObject("longPress")), json.optString("period", ",!?"))
        }
    }
}

class LayoutException(message: String) : Exception(message)
