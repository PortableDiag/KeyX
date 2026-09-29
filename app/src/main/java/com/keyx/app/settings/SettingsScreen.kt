package com.keyx.app.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings as SystemSettings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.keyx.app.data.KeyXData
import com.keyx.app.data.Settings

private val SWITCH_LABELS = mapOf(
    "autocorrect" to ("Autocorrect" to "Space fixes the word; backspace straight after puts it back"),
    "predictions" to ("Predictions" to "Completions and next-word suggestions in the strip"),
    "emoji_predictions" to ("Emoji predictions" to "\"pizza\" offers 🍕"),
    "swipe" to ("Swipe typing" to "Draw a word across the letters"),
    "auto_caps" to ("Auto-capitalize" to "Capital at the start of a sentence"),
    "double_space_period" to ("Double-space period" to "Two spaces type \". \""),
    "space_after_punctuation" to ("Space after punctuation" to "A space follows . , ! ? ; : — digits close it up again (3.14)"),
    "number_row" to ("Number row" to "1–0 above the letters"),
    "arrow_row" to ("Arrow keys row" to "← ↑ ↓ → below the keyboard"),
    "emoji_key" to ("Dedicated emoji key" to "Next to the comma"),
    "long_press_symbols" to ("Long-press for symbols" to "The small character on each key"),
    "all_accents" to ("All accents" to "Every accented form on long-press, not just the first"),
    "key_popup" to ("Key-press popup" to "The enlarged letter above your finger"),
    "voice" to ("Voice key" to "Long-press comma hands over to the system voice keyboard"),
    "clipboard_strip" to ("Clipboard strip" to "Offer a fresh copy in the strip; ClipX keeps the history"),
    "cursor_control" to ("Space bar cursor control" to "Long-press space, then drag"),
    "quick_delete" to ("Swipe-left backspace" to "Deletes a whole word"),
    "vibrate" to ("Vibrate on key press" to null),
)

@Composable
fun SettingsScreen(data: KeyXData, onEditLayout: (String) -> Unit, onEditTheme: () -> Unit) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(data.settings()) }
    var setup by remember { mutableStateOf(setupState(context)) }
    var stats by remember { mutableIntStateOf(0) } // bumps to re-read learned counts
    var message by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> settings = data.settings() }
        data.prefs.registerOnSharedPreferenceChangeListener(l)
        onDispose { data.prefs.unregisterOnSharedPreferenceChangeListener(l) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val o = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) { setup = setupState(context); stats++ }
        }
        lifecycle.addObserver(o)
        onDispose { lifecycle.removeObserver(o) }
    }

    val dictLanguage = data.layout(settings.currentLanguage).learning
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
        }.getOrNull()
        message = if (text == null) "Could not read that file" else {
            "Imported ${data.importWords(dictLanguage, text)} words into $dictLanguage"
        }
        stats++
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(data.learned.exportWords(dictLanguage).toByteArray())
            }
        }.isSuccess
        message = if (ok) "Exported $dictLanguage words" else "Could not write that file"
    }

    val edit = data.prefs.edit()
    Column(
        Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("KeyX", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text("An offline keyboard. No internet permission; what you type stays on this phone.",
            style = MaterialTheme.typography.bodyMedium)

        Section("Set up")
        StepRow("1. Turn KeyX on", setup.first) {
            context.startActivity(Intent(SystemSettings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        StepRow("2. Make it the keyboard", setup.second) {
            context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        }
        var trial by remember { mutableStateOf("") }
        OutlinedTextField(
            value = trial, onValueChange = { trial = it },
            modifier = Modifier.fillMaxWidth(), label = { Text("Try it here") },
        )

        Section("Languages")
        Text("Flick the space bar left or right to switch.", style = MaterialTheme.typography.bodySmall)
        for (id in data.bundledLanguages) {
            val layout = remember(id, stats) { data.layout(id) }
            val on = id in settings.languages
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = on, onCheckedChange = { checked ->
                    val next = if (checked) settings.languages + id else settings.languages - id
                    if (next.isNotEmpty()) {
                        edit.putString(Settings.LANGUAGES, next.joinToString(",")).apply()
                        if (!checked && settings.currentLanguage == id) {
                            edit.putString(Settings.CURRENT_LANGUAGE, next.first()).apply()
                        }
                    }
                })
                Text(layout.name + if (data.isLayoutEdited(id)) "  (edited)" else "", Modifier.weight(1f))
                TextButton(onClick = { onEditLayout(id) }) { Text("Edit layout") }
            }
        }

        Section("Theme")
        for (id in data.themeIds()) {
            val t = remember(id, stats) { data.theme(id) }
            Row(
                Modifier.fillMaxWidth().clickable { edit.putString(Settings.THEME, id).apply() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = settings.theme == id, onClick = { edit.putString(Settings.THEME, id).apply() })
                Box(
                    Modifier.size(width = 72.dp, height = 28.dp)
                        .background(Color(t.keyboardBackground), RoundedCornerShape(6.dp))
                        .border(1.dp, Color(t.keyBorder), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text("qwe", color = Color(t.keyText), fontFamily = FontFamily.Monospace) }
                Spacer(Modifier.width(12.dp))
                Text(t.name)
            }
        }
        TextButton(onClick = onEditTheme) { Text("Make a custom theme from the current one") }

        Section("Typing and layout")
        for ((key, get) in Settings.SWITCHES) {
            val (label, sub) = SWITCH_LABELS[key] ?: (key to null)
            SwitchRow(label, sub, get(settings)) { edit.putBoolean(key, it).apply() }
            if (key == "vibrate" && settings.vibrate) {
                SliderRow("Strength", settings.vibrateMs, 1..50, "${settings.vibrateMs} ms") {
                    edit.putInt(Settings.VIBRATE_MS, it).apply()
                }
            }
        }
        SliderRow("Keyboard height", settings.heightPercent, 70..140, "${settings.heightPercent}%") {
            edit.putInt(Settings.HEIGHT, (it / 5) * 5).apply()
        }

        Section("What KeyX has learned")
        val counts = remember(stats) {
            listOf("en", "de", "ru").associateWith { data.learned.wordCount(it) to data.learned.pairCount(it) }
        }
        for ((lang, c) in counts) {
            Text("$lang: ${c.first} words, ${c.second} word pairs", style = MaterialTheme.typography.bodyMedium)
        }
        Text("Never learned: password fields and incognito fields. Stored encrypted with a key held by Android Keystore.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { importer.launch(arrayOf("text/*")) }) { Text("Import words") }
            OutlinedButton(onClick = { exporter.launch("keyx-words-$dictLanguage.txt") }) { Text("Export") }
        }
        Text("Import takes a plain word list, one per line, optionally with a count; it goes into the " +
            "current language ($dictLanguage).", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { confirmReset = true }) { Text("Reset learning") }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        Section("About")
        Text("Word frequencies: FrequencyWords by Hermit Dave (OpenSubtitles 2018), CC BY-SA 4.0. " +
            "Emoji: Unicode emoji-test 15.1.", style = MaterialTheme.typography.bodySmall)
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset learning?") },
            text = { Text("Every learned word, word pair and recent emoji is deleted, with the key that encrypted them. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    data.resetLearning()
                    confirmReset = false
                    message = "Learning reset — starting fresh"
                    stats++
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

/** (enabled in system settings, selected as the current keyboard). */
private fun setupState(context: Context): Pair<Boolean, Boolean> {
    val imm = context.getSystemService(InputMethodManager::class.java)
    val enabled = imm?.enabledInputMethodList?.any { it.packageName == context.packageName } == true
    val current = SystemSettings.Secure.getString(context.contentResolver, SystemSettings.Secure.DEFAULT_INPUT_METHOD)
    return enabled to (current?.startsWith(context.packageName + "/") == true)
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.size(10.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun StepRow(label: String, done: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        if (done) Text("done", color = MaterialTheme.colorScheme.primary)
        else Button(onClick = onClick) { Text("Open") }
    }
}

@Composable
private fun SwitchRow(label: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(label: String, value: Int, range: IntRange, shown: String, onChange: (Int) -> Unit) {
    Column {
        Row { Text(label, Modifier.weight(1f)); Text(shown) }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}
