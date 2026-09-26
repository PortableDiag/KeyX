package com.keyx.app.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.keyx.app.layout.Page

/**
 * What a text field allows, decided once per field from its EditorInfo. Plain
 * ints in, so it is testable without a device.
 *
 * Password fields and IME_FLAG_NO_PERSONALIZED_LEARNING (incognito tabs) are
 * never learned from; password fields get no suggestions or corrections either.
 */
data class FieldPolicy(
    val page: Page,
    val suggest: Boolean,
    val autocorrect: Boolean,
    val learn: Boolean,
    /** TextUtils.CAP_MODE_* bits to ask the editor about, 0 for never. */
    val capsMode: Int,
    /** An EditorInfo.IME_ACTION_* for the enter key, or null for a newline. */
    val enterAction: Int?,
    val password: Boolean,
) {
    companion object {
        private const val CAP_CHARACTERS = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        private const val CAP_WORDS = InputType.TYPE_TEXT_FLAG_CAP_WORDS
        private const val CAP_SENTENCES = InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

        fun from(inputType: Int, imeOptions: Int): FieldPolicy {
            val cls = inputType and InputType.TYPE_MASK_CLASS
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            val flags = inputType and InputType.TYPE_MASK_FLAGS
            val multiLine = flags and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
            val action = imeOptions and EditorInfo.IME_MASK_ACTION
            val enter = when {
                imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0 -> null
                multiLine -> null
                action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED -> null
                else -> action
            }
            val noLearning = imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0

            if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE ||
                cls == InputType.TYPE_CLASS_DATETIME
            ) {
                val pin = cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
                val numberEnter = if (multiLine) null else (enter ?: EditorInfo.IME_ACTION_DONE)
                return FieldPolicy(Page.NUMBERS, false, false, false, 0, numberEnter, pin)
            }
            if (cls != InputType.TYPE_CLASS_TEXT) {
                // TYPE_NULL: a terminal or game reading raw keys. Type, but learn nothing.
                return FieldPolicy(Page.LETTERS, false, false, false, 0, enter, false)
            }
            val password = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            val machine = variation == InputType.TYPE_TEXT_VARIATION_URI ||
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_FILTER
            val noSuggestions = flags and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
            val suggest = !password && !machine
            val capsFlags = flags and (CAP_CHARACTERS or CAP_WORDS or CAP_SENTENCES)
            val caps = when {
                password || machine -> 0
                capsFlags != 0 -> capsFlags
                // Most chat fields forget the flag; SwiftKey capitalizes sentences anyway.
                variation == InputType.TYPE_TEXT_VARIATION_NORMAL ||
                    variation == InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE ||
                    variation == InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE ||
                    variation == InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT -> CAP_SENTENCES
                else -> 0
            }
            return FieldPolicy(
                page = Page.LETTERS,
                suggest = suggest,
                autocorrect = suggest && !noSuggestions,
                learn = !password && !machine && !noLearning,
                capsMode = caps,
                enterAction = enter,
                password = password,
            )
        }
    }
}
