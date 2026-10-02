package com.keyx.app.ime

import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.keyx.app.MainActivity
import com.keyx.app.data.KeyXData
import com.keyx.app.data.Settings
import com.keyx.app.layout.KeyType
import com.keyx.app.layout.KeyboardBuilder
import com.keyx.app.layout.Page
import com.keyx.app.predict.GestureDecoder
import com.keyx.app.predict.KeyGrid
import com.keyx.app.predict.Suggester
import com.keyx.app.predict.Suggestion

/**
 * KeyX's input method. Offline by construction: the app declares no INTERNET
 * permission, so nothing typed here can leave the phone through it.
 */
class KeyXService : InputMethodService(), KeyboardActions {
    private lateinit var data: KeyXData
    private lateinit var engine: InputEngine
    private var view: KeyboardView? = null
    private var settings = Settings()
    private var page = Page.LETTERS
    private var policy = FieldPolicy.from(EditorInfo.TYPE_CLASS_TEXT, 0)
    private var decoder: GestureDecoder? = null
    private val cursor = CursorTracker()
    private var dictionary: com.keyx.app.predict.Dictionary? = null

    /** Clips seen while the keyboard is running. Memory only: the history is ClipX's job. */
    private val clips = ArrayList<String>()
    private var chipShownAt = 0L

    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }
    private val vibrator by lazy { getSystemService(Vibrator::class.java) }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (view != null) applySettings()
    }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClip() }
    private val onReset: () -> Unit = {
        clips.clear()
        view?.clips = emptyList()
        view?.clipChip = null
        engine.refreshStrip()
    }

    override fun onCreate() {
        super.onCreate()
        data = KeyXData.get(this)
        engine = InputEngine(data.learned) { data.scheduleSave() }
        engine.listener = { render() }
        data.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        data.onReset(onReset)
        clipboard?.addPrimaryClipChangedListener(clipListener)
    }

    override fun onDestroy() {
        data.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        data.removeOnReset(onReset)
        clipboard?.removePrimaryClipChangedListener(clipListener)
        data.saveNow()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val v = KeyboardView(this, this)
        view = v
        applySettings()
        return v
    }

    /** Full-screen extract mode would hide the arrow row and the theme; never use it. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        policy = FieldPolicy.from(info.inputType, info.imeOptions)
        page = policy.page
        cursor.reset(info.initialSelEnd.takeIf { info.initialSelStart == info.initialSelEnd })
        engine.editor = ConnectionEditor()
        applySettings()
        view?.panel = Panel.KEYS
        engine.startInput(policy)
        view?.enterLabel = enterLabel(info)
        captureClip(fromStart = true)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        currentInputConnection?.finishComposingText()
        data.saveNow()
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (cursor.onUpdate(newSelStart, newSelEnd)) engine.onCursorMoved()
    }

    // ---- settings and layout ------------------------------------------------

    private fun applySettings() {
        val v = view ?: return
        val old = settings
        settings = data.settings()
        engine.options = settings.engineOptions
        v.theme = data.theme(settings.theme)
        v.swipeTyping = settings.swipe
        v.keyPopups = settings.keyPopup
        v.cursorControl = settings.cursorControl
        v.quickDelete = settings.quickDelete
        v.arrowRow = settings.arrowRow
        v.heightPercent = settings.heightPercent
        v.languageCount = settings.languages.size
        v.clipXInstalled = clipXInstalled()
        buildRows()
        if (old.currentLanguage != settings.currentLanguage || engine.suggester == null ||
            engine.suggester?.language != data.layout(settings.currentLanguage).learning
        ) {
            loadLanguage()
        }
        engine.refreshStrip()
    }

    private fun buildRows() {
        val v = view ?: return
        val layout = data.layout(settings.currentLanguage)
        v.rows = KeyboardBuilder.build(page, layout, data.symbols, settings.buildOptions)
        v.lettersPage = page == Page.LETTERS
        v.spaceLabel = if (page == Page.LETTERS) layout.spacebar else ""
        v.post { decoder = GestureDecoder(v.letterCenters(), v.keyUnit) }
    }

    private fun loadLanguage() {
        val layout = data.layout(settings.currentLanguage)
        engine.suggester = null
        dictionary = null
        data.dictionary(layout.dictionary) { dict ->
            if (dict == null || settings.currentLanguage != layout.id) return@dictionary
            dictionary = dict
            engine.suggester = Suggester(layout.learning, dict, data.learned, KeyGrid(layout.rows), data.emojiPredictor)
            engine.onLanguageChanged()
        }
    }

    private fun render() {
        val v = view ?: return
        v.shift = engine.shift
        v.strip = engine.strip
        if (engine.composingText.isNotEmpty() || System.currentTimeMillis() - chipShownAt > CHIP_MS) v.clipChip = null
    }

    private fun enterLabel(info: EditorInfo): String? = when (policy.enterAction) {
        EditorInfo.IME_ACTION_GO -> "go"
        EditorInfo.IME_ACTION_SEARCH -> "search"
        EditorInfo.IME_ACTION_SEND -> "send"
        EditorInfo.IME_ACTION_NEXT -> "next"
        EditorInfo.IME_ACTION_DONE -> "done"
        EditorInfo.IME_ACTION_PREVIOUS -> "prev"
        else -> info.actionLabel?.toString()
    }

    // ---- KeyboardActions ------------------------------------------------------

    override fun onChar(text: String) = engine.onText(text)

    override fun onSpace() {
        engine.onSpace()
        if (page == Page.SYMBOLS || page == Page.SYMBOLS_MORE) setPage(Page.LETTERS)
    }

    override fun onLanguageSwipe(forward: Boolean) {
        val langs = settings.languages
        if (langs.size < 2) return
        val i = langs.indexOf(settings.currentLanguage)
        val next = langs[Math.floorMod(i + if (forward) 1 else -1, langs.size)]
        engine.onCursorMoved() // commit the word in progress under the old language
        data.prefs.edit().putString(Settings.CURRENT_LANGUAGE, next).apply()
    }

    override fun onCursorSteps(steps: Int) {
        engine.onCursorMoved()
        val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        cursor.unknown()
        repeat(kotlin.math.abs(steps)) { sendDownUpKeyEvents(code) }
    }

    override fun onBackspace() = engine.onBackspace()

    override fun onDeleteWord() = engine.onDeleteWord()

    override fun onEnter() = engine.onEnter()

    override fun onShift(doubleTap: Boolean) = engine.onShift(doubleTap)

    override fun onPageKey(type: KeyType) = setPage(
        when (type) {
            KeyType.SYMBOLS -> Page.SYMBOLS
            KeyType.SYMBOLS_MORE -> Page.SYMBOLS_MORE
            else -> if (policy.page == Page.NUMBERS && page != Page.NUMBERS) Page.NUMBERS else Page.LETTERS
        },
    )

    private fun setPage(p: Page) {
        page = p
        buildRows()
    }

    override fun onVoice() {
        if (!settings.voice) return
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        for (imi in imm.enabledInputMethodList) {
            if (imi.packageName == packageName) continue
            val voice = imm.getEnabledInputMethodSubtypeList(imi, true).firstOrNull { it.mode == "voice" } ?: continue
            engine.onCursorMoved()
            // Hands over to the system's voice keyboard; KeyX has no microphone permission.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchInputMethod(imi.id, voice)
            } else {
                val token = window?.window?.attributes?.token ?: return
                @Suppress("DEPRECATION")
                imm.setInputMethodAndSubtype(token, imi.id, voice)
            }
            return
        }
        Toast.makeText(this, "No voice keyboard is enabled — turn one on under Languages & input", Toast.LENGTH_LONG).show()
    }

    override fun onArrow(keyCode: Int) {
        engine.onCursorMoved()
        cursor.unknown()
        sendDownUpKeyEvents(keyCode)
    }

    override fun onPick(s: Suggestion) = engine.onPick(s)

    override fun onRemoveSuggestion(word: String) = engine.onRemoveSuggestion(word)

    override fun onPasteClip(text: String) {
        view?.clipChip = null
        engine.onPaste(text)
        if (view?.panel == Panel.CLIPBOARD) view?.panel = Panel.KEYS
    }

    override fun onGesture(trace: List<GestureDecoder.Point>) {
        val d = decoder ?: return
        val dict = dictionary ?: return
        val lang = engine.suggester?.language ?: return
        val words = d.decode(trace, dict, bonus = { w -> 2.0 * kotlin.math.ln(1.0 + data.learned.count(lang, w)) })
        engine.onGesture(words.filterNot { data.learned.isBlocked(lang, it) })
    }

    override fun onEmoji(e: String) {
        engine.onEmoji(e)
        data.useEmoji(e)
    }

    override fun onTool(tool: Tool) {
        val v = view ?: return
        when (tool) {
            Tool.EMOJI -> {
                val recents = data.emojiRecents()
                val groups = data.emoji.groupBy({ it.first }, { it.second }).toList()
                v.emojiGroups = (if (recents.isEmpty()) groups else listOf("recent" to recents) + groups)
                v.panel = Panel.EMOJI
            }
            Tool.CLIPBOARD -> {
                v.clips = clips.toList()
                v.panel = Panel.CLIPBOARD
            }
            Tool.VOICE -> onVoice()
            Tool.SETTINGS -> startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Tool.HIDE -> requestHideSelf(0)
        }
    }

    override fun onOpenClipX() {
        val launch = packageManager.getLaunchIntentForPackage(CLIPX) ?: return
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onSaveToClipX(text: String) {
        // ClipX's share target saves a clipping and finishes; it has no UI to switch to.
        val send = Intent(Intent.ACTION_SEND).setPackage(CLIPX).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(send) }
            .onSuccess { Toast.makeText(this, "Saved to ClipX", Toast.LENGTH_SHORT).show() }
    }

    override fun onClearClips() {
        clips.clear()
        view?.clips = emptyList()
        view?.clipChip = null
    }

    override fun onKeyDown() {
        if (!settings.vibrate) return
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        vib.vibrate(VibrationEffect.createOneShot(settings.vibrateMs.toLong().coerceIn(1, 100), VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // ---- clipboard ------------------------------------------------------------

    private fun captureClip(fromStart: Boolean = false) {
        if (!settings.clipboardStrip || policy.password) return
        val clip = runCatching { clipboard?.primaryClip }.getOrNull() ?: return
        // Password managers mark their copies sensitive (Android 13+); those are never shown.
        if (clip.description?.extras?.getBoolean(EXTRA_IS_SENSITIVE) == true) return
        val text = clip.getItemAt(0)?.coerceToText(this)?.toString()?.takeIf { it.isNotBlank() } ?: return
        if (clips.firstOrNull() == text) return
        clips.remove(text)
        clips.add(0, text)
        while (clips.size > MAX_CLIPS) clips.removeAt(clips.size - 1)
        view?.clips = clips.toList()
        if (!fromStart || clips.size == 1) {
            chipShownAt = System.currentTimeMillis()
            view?.clipChip = text
        }
    }

    private fun clipXInstalled(): Boolean = packageManager.getLaunchIntentForPackage(CLIPX) != null

    /** The engine's view of the focused field. */
    private inner class ConnectionEditor : Editor {
        private val ic get() = currentInputConnection
        override fun before(n: Int) = ic?.getTextBeforeCursor(n, 0)?.toString() ?: ""
        override fun after(n: Int) = ic?.getTextAfterCursor(n, 0)?.toString() ?: ""
        override fun setComposing(text: String) {
            ic?.setComposingText(text, 1)
            cursor.setComposing(text.length)
        }
        override fun finishComposing() {
            ic?.finishComposingText()
            cursor.finishComposing()
        }
        override fun commit(text: String) {
            ic?.commitText(text, 1)
            cursor.commit(text.length)
        }
        override fun deleteBefore(n: Int) {
            ic?.deleteSurroundingText(n, 0)
            cursor.deleteBefore(n)
        }
        override fun composeBefore(word: String) {
            val c = ic ?: return
            // setComposingRegion needs absolute offsets; the extracted text has them.
            val et = c.getExtractedText(ExtractedTextRequest(), 0)
            if (et != null && et.selectionStart >= 0 && et.selectionStart == et.selectionEnd) {
                val end = et.startOffset + et.selectionEnd
                if (c.setComposingRegion(end - word.length, end)) {
                    cursor.composeBefore(word.length)
                    return
                }
            }
            // A field that will not say where its cursor is: rewrite the word instead.
            deleteBefore(word.length)
            setComposing(word)
        }
        override fun key(keyCode: Int) {
            cursor.unknown()
            sendDownUpKeyEvents(keyCode)
        }
        override fun editorAction(action: Int) { ic?.performEditorAction(action) }
        override fun capsMode(reqModes: Int) = ic?.getCursorCapsMode(reqModes) ?: 0
        override fun batch(block: () -> Unit) {
            val c = ic
            c?.beginBatchEdit()
            try {
                block()
            } finally {
                c?.endBatchEdit()
            }
        }
    }

    private companion object {
        const val CLIPX = "com.clipx.app"
        const val MAX_CLIPS = 10
        const val CHIP_MS = 60_000L
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}
