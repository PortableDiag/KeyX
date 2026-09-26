package com.keyx.app.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.widget.OverScroller
import com.keyx.app.layout.Key
import com.keyx.app.layout.KeyRow
import com.keyx.app.layout.KeyType
import com.keyx.app.predict.GestureDecoder
import com.keyx.app.predict.Strip
import com.keyx.app.predict.Suggestion
import com.keyx.app.theme.KeyboardTheme
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** What the keyboard view asks the service to do. */
interface KeyboardActions {
    fun onChar(text: String)
    fun onSpace()
    fun onLanguageSwipe(forward: Boolean)
    fun onCursorSteps(steps: Int)
    fun onBackspace()
    fun onDeleteWord()
    fun onEnter()
    fun onShift(doubleTap: Boolean)
    fun onPageKey(type: KeyType)
    fun onVoice()
    fun onArrow(keyCode: Int)
    fun onPick(s: Suggestion)
    fun onPasteClip(text: String)
    fun onGesture(trace: List<GestureDecoder.Point>)
    fun onEmoji(e: String)
    fun onTool(tool: Tool)
    fun onOpenClipX()
    fun onSaveToClipX(text: String)
    fun onClearClips()
    fun onKeyDown()
}

enum class Tool { CLIPBOARD, EMOJI, VOICE, SETTINGS, HIDE }
enum class Panel { KEYS, EMOJI, CLIPBOARD }

/**
 * The whole keyboard as one view: candidate strip, keys, arrow row, and the
 * emoji and clipboard panels that take the keys' place. One view means a key
 * preview or long-press popup on the top row can draw over the strip, as
 * SwiftKey's do, with no popup windows.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val actions: KeyboardActions) : View(context) {

    // ---- state the service sets -------------------------------------------

    var theme: KeyboardTheme? = null
        set(v) { field = v; invalidate() }
    var rows: List<KeyRow> = emptyList()
        set(v) { field = v; requestLayout(); computeKeys(); invalidate() }
    var shift = Shift.OFF
        set(v) { field = v; invalidate() }
    var spaceLabel = ""
        set(v) { field = v; invalidate() }
    var languageCount = 1
    /** Null draws the newline glyph. */
    var enterLabel: String? = null
        set(v) { field = v; invalidate() }
    var strip: Strip = Strip.EMPTY
        set(v) { field = v; invalidate() }
    /** A fresh clipboard item to offer in the strip, or null. */
    var clipChip: String? = null
        set(v) { field = v; invalidate() }
    var panel = Panel.KEYS
        set(v) {
            field = v
            panelScroll = 0f
            scroller.forceFinished(true)
            toolbarOpen = false
            requestLayout()
            invalidate()
        }
    var emojiGroups: List<Pair<String, List<String>>> = emptyList()
        set(v) { field = v; emojiLayoutDirty = true; invalidate() }
    var clips: List<String> = emptyList()
        set(v) { field = v; invalidate() }
    var clipXInstalled = false
    var swipeTyping = true
    var keyPopups = true
    var cursorControl = true
    var quickDelete = true
    var arrowRow = true
        set(v) { field = v; requestLayout() }
    var heightPercent = 100
        set(v) { field = v; requestLayout() }
    var lettersPage = true

    // ---- geometry -----------------------------------------------------------

    private val dp = resources.displayMetrics.density
    private val stripH get() = 46 * dp
    private val rowUnit get() = 54 * dp * heightPercent / 100f
    private val arrowH get() = if (arrowRow) 40 * dp else 0f
    private val padX get() = 2.5f * dp
    private val padY get() = 4f * dp
    private val corner get() = 7f * dp

    private class Box(val key: Key, val rect: RectF, val row: Int)

    private val boxes = ArrayList<Box>()
    private var keysTop = 0f
    private var keysBottom = 0f

    /** Width of a standard one-unit key, for gesture tolerances. */
    var keyUnit = 0f
        private set

    private fun keysHeight(): Float = rows.sumOf { (rowUnit * it.height).toDouble() }.toFloat()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = stripH + keysHeight() + arrowH
        setMeasuredDimension(w, h.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        computeKeys()
        emojiLayoutDirty = true
    }

    private fun computeKeys() {
        boxes.clear()
        val w = width.toFloat()
        if (w <= 0f) return
        keysTop = stripH
        var y = keysTop
        keyUnit = 0f
        rows.forEachIndexed { r, row ->
            val h = rowUnit * row.height
            val total = row.keys.sumOf { it.weight.toDouble() }.toFloat()
            val unit = w / total
            if (keyUnit == 0f || row.height == 1f && keyUnit < unit) keyUnit = unit
            var x = 0f
            for (k in row.keys) {
                val kw = unit * k.weight
                boxes += Box(k, RectF(x, y, x + kw, y + h), r)
                x += kw
            }
            y += h
        }
        keysBottom = y
    }

    /** Centers of the letter keys, for the swipe decoder. */
    fun letterCenters(): Map<Char, Pair<Float, Float>> =
        boxes.filter { it.key.type == KeyType.CHAR && it.key.text.length == 1 && it.key.text[0].isLetter() }
            .associate { it.key.text[0].lowercaseChar() to (it.rect.centerX() to it.rect.centerY()) }

    private fun boxAt(x: Float, y: Float): Box? {
        val inRow = boxes.filter { y >= it.rect.top && y < it.rect.bottom }
        if (inRow.isEmpty()) return null
        val hit = inRow.firstOrNull { x >= it.rect.left && x < it.rect.right }
        if (hit != null && hit.key.type != KeyType.SPACER) return hit
        // A touch on a row's side padding belongs to the nearest real key.
        return inRow.filter { it.key.type != KeyType.SPACER }
            .minByOrNull { min(abs(x - it.rect.left), abs(x - it.rect.right)) }
    }

    // ---- paint --------------------------------------------------------------

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val tmp = RectF()

    override fun onDraw(canvas: Canvas) {
        val t = theme ?: return
        canvas.drawColor(t.keyboardBackground)
        fill.color = t.stripBackground
        canvas.drawRect(0f, 0f, width.toFloat(), stripH, fill)
        drawStrip(canvas, t)
        when (panel) {
            Panel.KEYS -> {
                for (b in boxes) drawKey(canvas, t, b)
                if (mode == Mode.GESTURE) drawTrail(canvas, t)
            }
            Panel.EMOJI -> drawEmojiPanel(canvas, t)
            Panel.CLIPBOARD -> drawClipPanel(canvas, t)
        }
        if (arrowRow) drawArrows(canvas, t)
        drawPopups(canvas, t)
    }

    private fun centerText(canvas: Canvas, s: String, cx: Float, cy: Float, size: Float, color: Int) {
        text.textSize = size
        text.color = color
        val fm = text.fontMetrics
        canvas.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2f, text)
    }

    private fun faceRect(r: RectF): RectF {
        tmp.set(r.left + padX, r.top + padY, r.right - padX, r.bottom - padY)
        return tmp
    }

    private fun drawFace(canvas: Canvas, t: KeyboardTheme, r: RectF, pressed: Boolean, bordered: Boolean) {
        val f = faceRect(r)
        fill.color = if (pressed) t.keyPressed else t.keyFace
        if (pressed || (t.keyFace ushr 24) != 0) canvas.drawRoundRect(f, corner, corner, fill)
        if (bordered) {
            stroke.color = t.keyBorder
            stroke.strokeWidth = 1.2f * dp
            canvas.drawRoundRect(f, corner, corner, stroke)
        }
    }

    private fun drawKey(canvas: Canvas, t: KeyboardTheme, b: Box) {
        val k = b.key
        if (k.type == KeyType.SPACER) return
        val r = b.rect
        val pressed = pressedBox === b
        val h = r.height()
        val cx = r.centerX()
        val cy = r.centerY()
        when (k.type) {
            KeyType.CHAR -> {
                drawFace(canvas, t, r, pressed, true)
                val label = when (shift) {
                    Shift.OFF -> k.text
                    else -> k.text.uppercase()
                }
                val numberRow = b.row == 0 && rows.firstOrNull()?.height?.let { it < 1f } == true
                val size = if (numberRow) h * 0.42f else min(h * 0.42f, r.width() * 0.62f)
                if (k.hint != null && !numberRow) {
                    centerText(canvas, k.hint, cx, r.top + padY + h * 0.2f, h * 0.2f, t.hintText)
                    centerText(canvas, label, cx, cy + h * 0.1f, size, t.keyText)
                } else {
                    centerText(canvas, label, cx, cy, size, t.keyText)
                }
            }
            KeyType.SPACE -> {
                drawFace(canvas, t, r, pressed, true)
                centerText(canvas, spaceLabel, cx, cy, h * 0.24f, t.hintText)
                if (languageCount > 1) {
                    val s = h * 0.13f
                    triangle(canvas, r.left + padX + s * 2.2f, cy, s, left = true, color = t.functionText)
                    triangle(canvas, r.right - padX - s * 2.2f, cy, s, left = false, color = t.functionText)
                }
            }
            KeyType.SHIFT -> {
                drawFace(canvas, t, r, pressed, false)
                drawShift(canvas, t, cx, cy, h * 0.42f)
            }
            KeyType.BACKSPACE -> {
                drawFace(canvas, t, r, pressed, false)
                drawBackspace(canvas, t, cx, cy, h * 0.3f)
            }
            KeyType.ENTER -> {
                drawFace(canvas, t, r, pressed, false)
                val label = enterLabel
                if (label == null) drawEnter(canvas, t, cx, cy, h * 0.3f)
                else centerText(canvas, label, cx, cy, h * 0.27f, t.functionText)
            }
            KeyType.SYMBOLS, KeyType.SYMBOLS_MORE, KeyType.ALPHA -> {
                drawFace(canvas, t, r, pressed, false)
                centerText(canvas, k.text, cx, cy, h * if (k.text.length > 3) 0.26f else 0.38f, t.functionText)
            }
            KeyType.EMOJI -> {
                drawFace(canvas, t, r, pressed, false)
                drawSmiley(canvas, t, cx, cy, h * 0.2f)
            }
            KeyType.COMMA, KeyType.PERIOD -> {
                drawFace(canvas, t, r, pressed, false)
                if (k.hint == "mic") drawMic(canvas, t, cx, r.top + padY + h * 0.2f, h * 0.1f)
                else if (k.hint != null) centerText(canvas, k.hint, cx, r.top + padY + h * 0.2f, h * 0.2f, t.hintText)
                centerText(canvas, k.text, cx, cy + h * 0.1f, h * 0.42f, t.keyText)
            }
            KeyType.SPACER -> Unit
        }
    }

    private fun triangle(canvas: Canvas, cx: Float, cy: Float, s: Float, left: Boolean, color: Int) {
        path.reset()
        if (left) {
            path.moveTo(cx - s * 0.6f, cy); path.lineTo(cx + s * 0.6f, cy - s); path.lineTo(cx + s * 0.6f, cy + s)
        } else {
            path.moveTo(cx + s * 0.6f, cy); path.lineTo(cx - s * 0.6f, cy - s); path.lineTo(cx - s * 0.6f, cy + s)
        }
        path.close()
        fill.color = color
        canvas.drawPath(path, fill)
    }

    private fun upTriangle(canvas: Canvas, cx: Float, cy: Float, s: Float, up: Boolean, color: Int) {
        path.reset()
        val d = if (up) -1 else 1
        path.moveTo(cx, cy + d * s * 0.6f); path.lineTo(cx - s, cy - d * s * 0.6f); path.lineTo(cx + s, cy - d * s * 0.6f)
        path.close()
        fill.color = color
        canvas.drawPath(path, fill)
    }

    private fun chevron(canvas: Canvas, cx: Float, cy: Float, s: Float, keyCode: Int, color: Int) {
        path.reset()
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { path.moveTo(cx - s, cy + s / 2); path.lineTo(cx, cy - s / 2); path.lineTo(cx + s, cy + s / 2) }
            KeyEvent.KEYCODE_DPAD_DOWN -> { path.moveTo(cx - s, cy - s / 2); path.lineTo(cx, cy + s / 2); path.lineTo(cx + s, cy - s / 2) }
            KeyEvent.KEYCODE_DPAD_LEFT -> { path.moveTo(cx + s / 2, cy - s); path.lineTo(cx - s / 2, cy); path.lineTo(cx + s / 2, cy + s) }
            else -> { path.moveTo(cx - s / 2, cy - s); path.lineTo(cx + s / 2, cy); path.lineTo(cx - s / 2, cy + s) }
        }
        stroke.color = color
        stroke.strokeWidth = 2.2f * dp
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(path, stroke)
    }

    private fun drawShift(canvas: Canvas, t: KeyboardTheme, cx: Float, cy: Float, s: Float) {
        // An arrow: outline when off, filled when shifted, filled and underlined when locked.
        path.reset()
        val top = cy - s * 0.55f
        path.moveTo(cx, top)
        path.lineTo(cx + s * 0.5f, top + s * 0.5f)
        path.lineTo(cx + s * 0.22f, top + s * 0.5f)
        path.lineTo(cx + s * 0.22f, top + s * 0.95f)
        path.lineTo(cx - s * 0.22f, top + s * 0.95f)
        path.lineTo(cx - s * 0.22f, top + s * 0.5f)
        path.lineTo(cx - s * 0.5f, top + s * 0.5f)
        path.close()
        val color = if (shift == Shift.OFF) t.functionText else t.keyText
        if (shift == Shift.OFF) {
            stroke.color = color
            stroke.strokeWidth = 2f * dp
            stroke.strokeJoin = Paint.Join.ROUND
            canvas.drawPath(path, stroke)
        } else {
            fill.color = color
            canvas.drawPath(path, fill)
        }
        if (shift == Shift.LOCKED) {
            fill.color = color
            canvas.drawRect(cx - s * 0.5f, top + s * 1.1f, cx + s * 0.5f, top + s * 1.1f + 2.5f * dp, fill)
        }
    }

    private fun drawBackspace(canvas: Canvas, t: KeyboardTheme, cx: Float, cy: Float, s: Float) {
        path.reset()
        val w = s * 1.5f
        path.moveTo(cx - w, cy)
        path.lineTo(cx - w * 0.5f, cy - s * 0.6f)
        path.lineTo(cx + w, cy - s * 0.6f)
        path.lineTo(cx + w, cy + s * 0.6f)
        path.lineTo(cx - w * 0.5f, cy + s * 0.6f)
        path.close()
        fill.color = t.functionText
        canvas.drawPath(path, fill)
        stroke.color = t.keyboardBackground
        stroke.strokeWidth = 2.2f * dp
        stroke.strokeCap = Paint.Cap.ROUND
        val x0 = cx + w * 0.25f
        val d = s * 0.28f
        canvas.drawLine(x0 - d, cy - d, x0 + d, cy + d, stroke)
        canvas.drawLine(x0 - d, cy + d, x0 + d, cy - d, stroke)
    }

    private fun drawEnter(canvas: Canvas, t: KeyboardTheme, cx: Float, cy: Float, s: Float) {
        fill.color = t.functionText
        val w = s * 1.4f
        val bar = s * 0.32f
        // the vertical stem on the right, the horizontal bar, the arrowhead on the left
        canvas.drawRect(cx + w - bar, cy - s * 0.9f, cx + w, cy + bar / 2, fill)
        canvas.drawRect(cx - w + s * 0.6f, cy - bar / 2, cx + w, cy + bar / 2, fill)
        path.reset()
        path.moveTo(cx - w, cy)
        path.lineTo(cx - w + s * 0.7f, cy - s * 0.6f)
        path.lineTo(cx - w + s * 0.7f, cy + s * 0.6f)
        path.close()
        canvas.drawPath(path, fill)
    }

    private fun drawSmiley(canvas: Canvas, t: KeyboardTheme, cx: Float, cy: Float, r: Float) {
        stroke.color = t.functionText
        stroke.strokeWidth = 1.8f * dp
        canvas.drawCircle(cx, cy, r, stroke)
        fill.color = t.functionText
        canvas.drawCircle(cx - r * 0.35f, cy - r * 0.25f, r * 0.12f, fill)
        canvas.drawCircle(cx + r * 0.35f, cy - r * 0.25f, r * 0.12f, fill)
        tmp.set(cx - r * 0.5f, cy - r * 0.3f, cx + r * 0.5f, cy + r * 0.55f)
        canvas.drawArc(tmp, 20f, 140f, false, stroke)
    }

    private fun drawMic(canvas: Canvas, t: KeyboardTheme, cx: Float, cy: Float, s: Float) {
        fill.color = t.hintText
        tmp.set(cx - s * 0.45f, cy - s, cx + s * 0.45f, cy + s * 0.3f)
        canvas.drawRoundRect(tmp, s * 0.45f, s * 0.45f, fill)
        stroke.color = t.hintText
        stroke.strokeWidth = 1.2f * dp
        tmp.set(cx - s * 0.75f, cy - s * 0.4f, cx + s * 0.75f, cy + s * 0.75f)
        canvas.drawArc(tmp, 0f, 180f, false, stroke)
        canvas.drawLine(cx, cy + s * 0.75f, cx, cy + s * 1.1f, stroke)
    }

    // ---- strip --------------------------------------------------------------

    private var toolbarOpen = false
    private val tools = listOf(Tool.CLIPBOARD to "clipboard", Tool.EMOJI to "emoji", Tool.VOICE to "voice",
        Tool.SETTINGS to "settings", Tool.HIDE to "hide")

    private fun stripSlots(): List<RectF> {
        val left = stripH
        val w = (width - left) / 3f
        return (0 until 3).map { RectF(left + it * w, 0f, left + (it + 1) * w, stripH) }
    }

    private fun toolSlots(): List<RectF> {
        val left = stripH
        val w = (width - left) / tools.size
        return tools.indices.map { RectF(left + it * w, 0f, left + (it + 1) * w, stripH) }
    }

    private fun drawStrip(canvas: Canvas, t: KeyboardTheme) {
        // The toolbar toggle, SwiftKey's circle on the left.
        val r = stripH * 0.3f
        fill.color = t.keyPressed
        canvas.drawCircle(stripH / 2, stripH / 2, r, fill)
        upTriangle(canvas, stripH / 2, stripH / 2, r * 0.4f, up = !toolbarOpen, color = t.functionText)

        if (toolbarOpen) {
            toolSlots().forEachIndexed { i, s ->
                centerText(canvas, tools[i].second, s.centerX(), s.centerY(), stripH * 0.3f, t.candidateText)
            }
            return
        }
        val chip = clipChip
        if (chip != null && strip.all.none { it.kind == Suggestion.Kind.TYPED || it.kind == Suggestion.Kind.CORRECTION }) {
            val s = RectF(stripH, 0f, width.toFloat(), stripH)
            text.textSize = stripH * 0.34f
            val shown = TextUtils.ellipsize("paste: " + chip.replace('\n', ' '), text, s.width() - 24 * dp, TextUtils.TruncateAt.END)
            centerText(canvas, shown.toString(), s.centerX(), s.centerY(), stripH * 0.34f, t.candidateText)
            return
        }
        val slots = stripSlots()
        listOf(strip.left, strip.center, strip.right).forEachIndexed { i, s ->
            if (s == null) return@forEachIndexed
            val slot = slots[i]
            if (pressedStrip == i) {
                fill.color = t.keyPressed
                canvas.drawRect(slot, fill)
            }
            val label = if (s.kind == Suggestion.Kind.TYPED && i != 1) "“${s.text}”" else s.text
            val size = stripH * if (i == 1) 0.5f else 0.44f
            text.textSize = size
            val shown = TextUtils.ellipsize(label, text, slot.width() - 8 * dp, TextUtils.TruncateAt.END)
            centerText(canvas, shown.toString(), slot.centerX(), slot.centerY(), size,
                if (i == 1) t.candidateHighlight else t.candidateText)
        }
    }

    // ---- arrow row ----------------------------------------------------------

    /** SwiftKey's order: up, down, left, right. */
    private val arrowCodes = listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)

    private fun arrowSlots(): List<RectF> {
        val top = height - arrowH
        val w = width / 4f
        return (0 until 4).map { RectF(it * w, top, (it + 1) * w, height.toFloat()) }
    }

    private fun drawArrows(canvas: Canvas, t: KeyboardTheme) {
        arrowSlots().forEachIndexed { i, r ->
            val pressed = pressedArrow == i
            drawFace(canvas, t, r, pressed, false)
            // Chevrons, as SwiftKey draws them.
            chevron(canvas, r.centerX(), r.centerY(), r.height() * 0.16f, arrowCodes[i], t.functionText)
        }
    }

    // ---- popups ---------------------------------------------------------------

    private var popupOptions: List<String> = emptyList()
    private val popupCells = ArrayList<RectF>()
    private var popupSelected = 0

    private fun drawPopups(canvas: Canvas, t: KeyboardTheme) {
        val b = pressedBox
        if (popupOptions.isNotEmpty()) {
            popupCells.forEachIndexed { i, c ->
                fill.color = if (i == popupSelected) t.keyPressed else t.popupBackground
                canvas.drawRect(c, fill)
                centerText(canvas, popupOptions[i], c.centerX(), c.centerY(), c.height() * 0.5f,
                    if (i == popupSelected) t.candidateHighlight else t.keyText)
            }
            val outline = RectF(popupCells.minOf { it.left }, popupCells.minOf { it.top },
                popupCells.maxOf { it.right }, popupCells.maxOf { it.bottom })
            stroke.color = t.popupBorder
            stroke.strokeWidth = 1.5f * dp
            canvas.drawRoundRect(outline, corner, corner, stroke)
            return
        }
        if (b != null && keyPopups && mode == Mode.KEY && b.key.type == KeyType.CHAR) {
            val w = b.rect.width() * 1.15f
            val h = b.rect.height() * 1.1f
            val left = (b.rect.centerX() - w / 2).coerceIn(0f, width - w)
            val top = max(0f, b.rect.top - h)
            tmp.set(left, top, left + w, top + h)
            fill.color = t.popupBackground
            canvas.drawRoundRect(tmp, corner, corner, fill)
            stroke.color = t.popupBorder
            stroke.strokeWidth = 1.5f * dp
            canvas.drawRoundRect(tmp, corner, corner, stroke)
            val label = if (shift == Shift.OFF) b.key.text else b.key.text.uppercase()
            centerText(canvas, label, tmp.centerX(), tmp.centerY(), h * 0.5f, t.keyText)
        }
    }

    private fun openPopup(b: Box, options: List<String>) {
        popupOptions = if (shift == Shift.OFF) options else options.map { it.uppercase() }
        popupCells.clear()
        val cellW = min(b.rect.width(), width / 8f).coerceAtLeast(36 * dp)
        val cellH = b.rect.height() * 0.95f
        val perRow = max(1, min(options.size, (width / cellW).toInt()))
        val rowsN = (options.size + perRow - 1) / perRow
        val totalW = perRow * cellW
        val left = (b.rect.centerX() - cellW / 2).coerceIn(0f, width - totalW)
        val top = max(0f, b.rect.top - cellH * rowsN)
        options.indices.forEach { i ->
            val r = i / perRow
            val c = i % perRow
            popupCells += RectF(left + c * cellW, top + r * cellH, left + (c + 1) * cellW, top + (r + 1) * cellH)
        }
        popupSelected = 0
        invalidate()
    }

    private fun trackPopup(x: Float, y: Float) {
        if (popupCells.isEmpty()) return
        val i = popupCells.indices.minByOrNull { i ->
            val c = popupCells[i]
            val dx = if (x < c.left) c.left - x else if (x > c.right) x - c.right else 0f
            val dy = if (y < c.top) c.top - y else if (y > c.bottom) y - c.bottom else 0f
            dx + dy * 0.5f
        } ?: 0
        if (i != popupSelected) { popupSelected = i; invalidate() }
    }

    private fun closePopup() {
        popupOptions = emptyList()
        popupCells.clear()
    }

    // ---- gesture trail --------------------------------------------------------

    private val trail = ArrayList<GestureDecoder.Point>()

    private fun drawTrail(canvas: Canvas, t: KeyboardTheme) {
        if (trail.size < 2) return
        path.reset()
        path.moveTo(trail[0].x, trail[0].y)
        for (i in 1 until trail.size) path.lineTo(trail[i].x, trail[i].y)
        stroke.color = t.gestureTrail
        stroke.strokeWidth = 5f * dp
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(path, stroke)
    }

    // ---- emoji panel ----------------------------------------------------------

    private val emojiCols = 8
    private var emojiLayoutDirty = true
    private val emojiCells = ArrayList<Pair<String, RectF>>() // in content coordinates
    private val groupStarts = ArrayList<Float>()
    private var contentHeight = 0f
    private var panelScroll = 0f
    private val scroller = OverScroller(context)

    private val tabIcons = mapOf(
        "recent" to "🕘", "smileys" to "😀", "people" to "👋", "nature" to "🐶", "food" to "🍔",
        "travel" to "✈️", "activities" to "⚽", "objects" to "💡", "symbols" to "❤️", "flags" to "🏁",
    )

    private fun panelTop() = keysTop
    private fun panelBottom() = keysTop + keysHeight()
    private fun tabH() = 40 * dp
    private fun controlH() = rowUnit
    private fun gridTop() = panelTop() + tabH()
    private fun gridBottom() = panelBottom() - controlH()

    private fun layoutEmoji() {
        if (!emojiLayoutDirty || width == 0) return
        emojiCells.clear()
        groupStarts.clear()
        val cw = width / emojiCols.toFloat()
        val ch = min(cw, 48 * dp)
        var y = 0f
        for ((_, list) in emojiGroups) {
            groupStarts += y
            list.forEachIndexed { i, e ->
                val c = i % emojiCols
                if (i > 0 && c == 0) y += ch
                emojiCells += e to RectF(c * cw, y, (c + 1) * cw, y + ch)
            }
            y += ch
        }
        contentHeight = y
        emojiLayoutDirty = false
    }

    private fun maxScroll(viewH: Float) = max(0f, contentHeight - viewH)

    private fun drawEmojiPanel(canvas: Canvas, t: KeyboardTheme) {
        layoutEmoji()
        // tabs
        val tw = width / max(1, emojiGroups.size).toFloat()
        val current = groupStarts.indexOfLast { it <= panelScroll + 1 }.coerceAtLeast(0)
        emojiGroups.forEachIndexed { i, (g, _) ->
            val r = RectF(i * tw, panelTop(), (i + 1) * tw, panelTop() + tabH())
            if (i == current) {
                fill.color = t.keyPressed
                canvas.drawRect(r, fill)
            }
            centerText(canvas, tabIcons[g] ?: "•", r.centerX(), r.centerY(), tabH() * 0.5f, t.keyText)
        }
        // grid
        canvas.save()
        canvas.clipRect(0f, gridTop(), width.toFloat(), gridBottom())
        val top = gridTop() - panelScroll
        for ((e, r) in emojiCells) {
            if (r.bottom + top < gridTop() || r.top + top > gridBottom()) continue
            centerText(canvas, e, r.centerX(), r.centerY() + top, r.height() * 0.62f, t.keyText)
        }
        canvas.restore()
        drawPanelControls(canvas, t, listOf("ABC", " ", "⌫"))
    }

    private fun controlSlots(n: Int, weights: List<Float>? = null): List<RectF> {
        val top = gridBottom()
        val ws = weights ?: List(n) { 1f }
        val unit = width / ws.sum()
        var x = 0f
        return ws.map { w -> RectF(x, top, x + unit * w, panelBottom()).also { x += unit * w } }
    }

    private fun drawPanelControls(canvas: Canvas, t: KeyboardTheme, labels: List<String>) {
        val slots = controlSlots(labels.size, if (panel == Panel.EMOJI) listOf(1.5f, 5f, 1.5f) else null)
        slots.forEachIndexed { i, r ->
            val pressed = pressedControl == i
            drawFace(canvas, t, r, pressed, labels[i] == " ")
            when (labels[i]) {
                "⌫" -> drawBackspace(canvas, t, r.centerX(), r.centerY(), r.height() * 0.3f)
                " " -> Unit
                else -> centerText(canvas, labels[i], r.centerX(), r.centerY(), r.height() * 0.28f, t.functionText)
            }
        }
    }

    // ---- clipboard panel --------------------------------------------------------

    private fun clipRowH() = 44 * dp

    private fun clipControls(): List<String> =
        if (clipXInstalled) listOf("ABC", "open ClipX", "save to ClipX", "clear") else listOf("ABC", "clear")

    private fun drawClipPanel(canvas: Canvas, t: KeyboardTheme) {
        val title = if (clipXInstalled) "This session only — the history lives in ClipX"
        else "This session only — install ClipX for a history"
        centerText(canvas, title, width / 2f, panelTop() + tabH() / 2, tabH() * 0.34f, t.hintText)
        canvas.save()
        canvas.clipRect(0f, gridTop(), width.toFloat(), gridBottom())
        if (clips.isEmpty()) {
            centerText(canvas, "Nothing copied yet", width / 2f, (gridTop() + gridBottom()) / 2, clipRowH() * 0.36f, t.candidateText)
        }
        clips.forEachIndexed { i, c ->
            val top = gridTop() + i * clipRowH() - panelScroll
            val r = RectF(0f, top, width.toFloat(), top + clipRowH())
            drawFace(canvas, t, r, pressedClip == i, true)
            text.textSize = clipRowH() * 0.36f
            val shown = TextUtils.ellipsize(c.replace('\n', ' '), text, width - 32 * dp, TextUtils.TruncateAt.END)
            centerText(canvas, shown.toString(), r.centerX(), r.centerY(), clipRowH() * 0.36f, t.keyText)
        }
        contentHeight = clips.size * clipRowH()
        canvas.restore()
        drawPanelControls(canvas, t, clipControls())
    }

    // ---- touch ------------------------------------------------------------------

    private enum class Mode { NONE, KEY, GESTURE, POPUP, SPACE, CURSOR, BACKSPACE, STRIP, ARROW, PANEL, CONTROL }

    private var mode = Mode.NONE
    private var pointerId = -1
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var pressedBox: Box? = null
    private var pressedStrip = -1
    private var pressedArrow = -1
    private var pressedControl = -1
    private var pressedClip = -1
    private var cursorAnchorX = 0f
    private var cursorSent = 0
    private var repeats = 0
    private var lastShiftTap = 0L
    private var velocity: VelocityTracker? = null
    private var panelDragging = false
    private var lastPanelY = 0f

    private val handler = Handler(Looper.getMainLooper())

    private val longPress = Runnable {
        val b = pressedBox ?: return@Runnable
        when (mode) {
            Mode.KEY -> when {
                b.key.type == KeyType.COMMA && b.key.hint == "mic" -> {
                    actions.onVoice()
                    mode = Mode.NONE
                    pressedBox = null
                    invalidate()
                }
                b.key.popup.isNotEmpty() -> {
                    mode = Mode.POPUP
                    openPopup(b, b.key.popup)
                    actions.onKeyDown()
                }
            }
            Mode.SPACE -> if (cursorControl) {
                mode = Mode.CURSOR
                cursorAnchorX = lastX
                cursorSent = 0
                actions.onKeyDown()
            }
            else -> Unit
        }
    }

    private val repeat = object : Runnable {
        override fun run() {
            when (mode) {
                Mode.BACKSPACE -> {
                    repeats++
                    // Held long enough, it deletes whole words, as SwiftKey's does.
                    if (repeats > 15) actions.onDeleteWord() else actions.onBackspace()
                    handler.postDelayed(this, if (repeats > 15) 180 else 55)
                }
                Mode.ARROW -> {
                    repeats++
                    actions.onArrow(arrowCodes[pressedArrow])
                    handler.postDelayed(this, 60)
                }
                else -> Unit
            }
        }
    }

    private var lastX = 0f

    override fun performClick(): Boolean = super.performClick()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = e.getPointerId(0)
                down(e.x, e.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Two-thumb typing: a second finger lands before the first lifts —
                // on a letter, the space bar or backspace alike. The first key is
                // typed now and the new finger takes over, so no key is dropped.
                val rolling = mode == Mode.KEY || mode == Mode.SPACE ||
                    (mode == Mode.BACKSPACE && repeats == 0) || mode == Mode.NONE
                if (rolling) {
                    val i = e.findPointerIndex(pointerId)
                    if (i >= 0 && mode != Mode.NONE) up(e.getX(i), e.getY(i))
                    val n = e.actionIndex
                    pointerId = e.getPointerId(n)
                    down(e.getX(n), e.getY(n))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(pointerId)
                if (i >= 0) {
                    velocity?.addMovement(e)
                    move(e.getX(i), e.getY(i))
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) == pointerId) {
                    up(e.getX(e.actionIndex), e.getY(e.actionIndex))
                    pointerId = -1
                }
            }
            MotionEvent.ACTION_UP -> {
                val i = e.findPointerIndex(pointerId)
                if (i >= 0) up(e.getX(i), e.getY(i))
                pointerId = -1
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> reset()
        }
        return true
    }

    private fun down(x: Float, y: Float) {
        downX = x; downY = y; lastX = x
        downTime = SystemClock.uptimeMillis()
        repeats = 0
        handler.removeCallbacks(longPress)
        handler.removeCallbacks(repeat)
        when {
            y < stripH -> {
                mode = Mode.STRIP
                pressedStrip = if (x < stripH) -2 else if (toolbarOpen) -1 else stripSlots().indexOfFirst { it.contains(x, y) }
            }
            arrowRow && y >= height - arrowH -> {
                mode = Mode.ARROW
                pressedArrow = arrowSlots().indexOfFirst { x >= it.left && x < it.right }.coerceIn(0, 3)
                actions.onKeyDown()
                actions.onArrow(arrowCodes[pressedArrow])
                handler.postDelayed(repeat, 400)
            }
            panel != Panel.KEYS -> panelDown(x, y)
            else -> keyDown(x, y)
        }
        invalidate()
    }

    private fun keyDown(x: Float, y: Float) {
        val b = boxAt(x, y) ?: run { mode = Mode.NONE; return }
        pressedBox = b
        actions.onKeyDown()
        when (b.key.type) {
            KeyType.SPACE -> {
                mode = Mode.SPACE
                handler.postDelayed(longPress, SpaceGesture.LONG_PRESS_MS)
            }
            KeyType.BACKSPACE -> {
                mode = Mode.BACKSPACE
                handler.postDelayed(repeat, 400)
            }
            KeyType.SHIFT -> {
                val now = SystemClock.uptimeMillis()
                actions.onShift(doubleTap = now - lastShiftTap < 350)
                lastShiftTap = now
                mode = Mode.NONE
            }
            else -> {
                mode = Mode.KEY
                if (b.key.popup.isNotEmpty() || b.key.hint == "mic") handler.postDelayed(longPress, 400)
            }
        }
    }

    private fun move(x: Float, y: Float) {
        lastX = x
        when (mode) {
            Mode.KEY -> {
                val b = pressedBox ?: return
                // A swipe has left its key; a fast, sloppy tap has only smeared on it.
                val far = hypot(x - downX, y - downY) > keyUnit * 0.6f && !b.rect.contains(x, y)
                val letter = b.key.type == KeyType.CHAR && b.key.text.length == 1 && b.key.text[0].isLetter()
                if (far && swipeTyping && lettersPage && letter) {
                    handler.removeCallbacks(longPress)
                    mode = Mode.GESTURE
                    trail.clear()
                    trail += GestureDecoder.Point(downX, downY)
                    trail += GestureDecoder.Point(x, y)
                    invalidate()
                }
            }
            Mode.GESTURE -> {
                trail += GestureDecoder.Point(x, y)
                invalidate()
            }
            Mode.POPUP -> trackPopup(x, y)
            Mode.SPACE -> {
                if (!SpaceGesture.startsCursor(x - downX, y - downY, keyUnit)) handler.removeCallbacks(longPress)
            }
            Mode.CURSOR -> {
                val steps = SpaceGesture.cursorSteps(x - cursorAnchorX, keyUnit)
                if (steps != cursorSent) {
                    actions.onCursorSteps(steps - cursorSent)
                    cursorSent = steps
                }
            }
            Mode.PANEL -> panelMove(y)
            else -> Unit
        }
    }

    private fun up(x: Float, y: Float) {
        handler.removeCallbacks(longPress)
        handler.removeCallbacks(repeat)
        val b = pressedBox
        when (mode) {
            Mode.KEY -> if (b != null) tap(b.key)
            Mode.GESTURE -> {
                trail += GestureDecoder.Point(x, y)
                actions.onGesture(ArrayList(trail))
                trail.clear()
            }
            Mode.POPUP -> {
                popupOptions.getOrNull(popupSelected)?.let { actions.onChar(it) }
                closePopup()
            }
            Mode.SPACE -> when (SpaceGesture.onRelease(x - downX, y - downY, SystemClock.uptimeMillis() - downTime,
                false, keyUnit, languageCount)) {
                SpaceGesture.Result.SPACE -> actions.onSpace()
                SpaceGesture.Result.LANGUAGE_NEXT -> actions.onLanguageSwipe(true)
                SpaceGesture.Result.LANGUAGE_PREVIOUS -> actions.onLanguageSwipe(false)
                SpaceGesture.Result.NONE -> Unit
            }
            Mode.BACKSPACE -> if (repeats == 0) {
                if (quickDelete && x - downX < -keyUnit) actions.onDeleteWord() else actions.onBackspace()
            }
            Mode.STRIP -> stripUp(x, y)
            Mode.PANEL, Mode.CONTROL -> panelUp(x, y)
            else -> Unit
        }
        reset()
    }

    private fun tap(k: Key) {
        when (k.type) {
            KeyType.CHAR, KeyType.COMMA, KeyType.PERIOD -> actions.onChar(k.text)
            KeyType.ENTER -> actions.onEnter()
            KeyType.SYMBOLS, KeyType.SYMBOLS_MORE, KeyType.ALPHA -> actions.onPageKey(k.type)
            KeyType.EMOJI -> actions.onTool(Tool.EMOJI)
            KeyType.SPACE -> actions.onSpace()
            else -> Unit
        }
    }

    private fun stripUp(x: Float, y: Float) {
        if (y > stripH * 1.5f) return
        if (pressedStrip == -2) {
            toolbarOpen = !toolbarOpen
            return
        }
        if (toolbarOpen) {
            val i = toolSlots().indexOfFirst { x >= it.left && x < it.right }
            if (i >= 0) {
                toolbarOpen = false
                actions.onTool(tools[i].first)
            }
            return
        }
        val chip = clipChip
        if (chip != null && strip.all.none { it.kind == Suggestion.Kind.TYPED || it.kind == Suggestion.Kind.CORRECTION }) {
            actions.onPasteClip(chip)
            return
        }
        val s = listOf(strip.left, strip.center, strip.right).getOrNull(pressedStrip) ?: return
        actions.onPick(s)
    }

    private fun panelDown(x: Float, y: Float) {
        scroller.forceFinished(true)
        velocity?.recycle()
        velocity = VelocityTracker.obtain()
        panelDragging = false
        lastPanelY = y
        mode = Mode.PANEL
        pressedClip = -1
        pressedControl = -1
        if (y >= gridBottom()) {
            mode = Mode.CONTROL
            val labels = if (panel == Panel.EMOJI) 3 else clipControls().size
            val slots = controlSlots(labels, if (panel == Panel.EMOJI) listOf(1.5f, 5f, 1.5f) else null)
            pressedControl = slots.indexOfFirst { x >= it.left && x < it.right }
            actions.onKeyDown()
            if (panel == Panel.EMOJI && pressedControl == 2) {
                mode = Mode.BACKSPACE
                handler.postDelayed(repeat, 400)
            }
        } else if (panel == Panel.CLIPBOARD && y >= gridTop()) {
            val i = ((y - gridTop() + panelScroll) / clipRowH()).toInt()
            pressedClip = if (i in clips.indices) i else -1
        }
    }

    private fun panelMove(y: Float) {
        if (!panelDragging && abs(y - downY) > 8 * dp && downY >= gridTop()) {
            panelDragging = true
            pressedClip = -1
        }
        if (panelDragging) {
            val viewH = gridBottom() - gridTop()
            panelScroll = (panelScroll - (y - lastPanelY)).coerceIn(0f, maxScroll(viewH))
            lastPanelY = y
            invalidate()
        }
    }

    private fun panelUp(x: Float, y: Float) {
        if (mode == Mode.CONTROL) {
            controlTap(pressedControl)
            return
        }
        if (panelDragging) {
            val v = velocity ?: return
            v.computeCurrentVelocity(1000)
            val viewH = gridBottom() - gridTop()
            scroller.fling(0, panelScroll.toInt(), 0, -v.yVelocity.toInt(), 0, 0, 0, maxScroll(viewH).toInt())
            postInvalidateOnAnimation()
            return
        }
        when {
            y < gridTop() && panel == Panel.EMOJI -> {
                val tw = width / max(1, emojiGroups.size).toFloat()
                val i = (x / tw).toInt()
                groupStarts.getOrNull(i)?.let {
                    panelScroll = min(it, maxScroll(gridBottom() - gridTop()))
                    invalidate()
                }
            }
            panel == Panel.EMOJI -> {
                val cy = y - gridTop() + panelScroll
                emojiCells.firstOrNull { it.second.contains(x, cy) }?.let { actions.onEmoji(it.first) }
            }
            panel == Panel.CLIPBOARD -> clips.getOrNull(pressedClip)?.let { actions.onPasteClip(it) }
        }
    }

    private fun controlTap(i: Int) {
        if (panel == Panel.EMOJI) {
            when (i) {
                0 -> panel = Panel.KEYS
                1 -> actions.onSpace()
            }
            return
        }
        when (clipControls().getOrNull(i)) {
            "ABC" -> panel = Panel.KEYS
            "open ClipX" -> actions.onOpenClipX()
            "save to ClipX" -> clips.firstOrNull()?.let { actions.onSaveToClipX(it) }
            "clear" -> actions.onClearClips()
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            panelScroll = scroller.currY.toFloat()
            postInvalidateOnAnimation()
        }
    }

    private fun reset() {
        handler.removeCallbacks(longPress)
        handler.removeCallbacks(repeat)
        mode = Mode.NONE
        pressedBox = null
        pressedStrip = -1
        pressedArrow = -1
        pressedControl = -1
        pressedClip = -1
        closePopup()
        trail.clear()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
        velocity?.recycle()
        velocity = null
    }
}
