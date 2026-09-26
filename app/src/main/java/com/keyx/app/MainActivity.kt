package com.keyx.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import android.content.SharedPreferences
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.keyx.app.data.KeyXData
import com.keyx.app.settings.JsonEditor
import com.keyx.app.settings.SettingsScreen
import com.keyx.app.ui.theme.AppTheme

/** KeyX's settings: setup, languages, theme, every switch, and the learned words. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = KeyXData.get(this)
        setContent {
            // Follows the keyboard theme live: picking one in the list below re-themes this screen.
            var themeId by remember { mutableStateOf(data.settings().theme) }
            DisposableEffect(Unit) {
                val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> themeId = data.settings().theme }
                data.prefs.registerOnSharedPreferenceChangeListener(l)
                onDispose { data.prefs.unregisterOnSharedPreferenceChangeListener(l) }
            }
            val theme = remember(themeId) { data.theme(themeId) }
            val view = LocalView.current
            SideEffect {
                window.statusBarColor = theme.appBackground
                window.navigationBarColor = theme.appBackground
                val light = Color(theme.appBackground).luminance() >= 0.5f
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = light
                    isAppearanceLightNavigationBars = light
                }
            }
            AppTheme(theme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    // "" is the main screen; "layout:<id>" and "theme" are the JSON editors.
                    var route by rememberSaveable { mutableStateOf("") }
                    BackHandler(enabled = route.isNotEmpty()) { route = "" }
                    when {
                        route.startsWith("layout:") -> {
                            val id = route.removePrefix("layout:")
                            JsonEditor(
                                title = "Layout — ${data.layout(id).name}",
                                help = "Rows, number row, long-press hints and accents. Saved only if it parses.",
                                initial = data.layoutText(id),
                                bundled = data.bundledLayoutText(id),
                                onSave = { text -> data.saveLayout(id, text).name },
                                onRevert = { data.resetLayout(id) },
                                onClose = { route = "" },
                            )
                        }
                        route == "theme" -> JsonEditor(
                            title = "Custom theme",
                            help = "Colors as #RRGGBB or #AARRGGBB. The id must stay \"custom\".",
                            initial = data.themeText(data.settings().theme)
                                .replace(Regex("\"id\"\\s*:\\s*\"[^\"]*\""), "\"id\": \"${KeyXData.CUSTOM_THEME}\""),
                            bundled = null,
                            onSave = { text ->
                                val t = data.saveCustomTheme(text)
                                data.prefs.edit().putString(com.keyx.app.data.Settings.THEME, t.id).apply()
                                t.name
                            },
                            onRevert = null,
                            onClose = { route = "" },
                        )
                        else -> SettingsScreen(data, onEditLayout = { route = "layout:$it" }, onEditTheme = { route = "theme" })
                    }
                }
            }
        }
    }
}
