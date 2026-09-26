package com.keyx.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Edits a layout or theme as the JSON it is stored as. [onSave] parses and
 * throws with a readable message on a bad edit, so nothing broken is saved.
 */
@Composable
fun JsonEditor(
    title: String,
    help: String,
    initial: String,
    bundled: String?,
    onSave: (String) -> String,
    onRevert: (() -> Unit)?,
    onClose: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var status by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Text(help, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; status = null },
            modifier = Modifier.fillMaxWidth().weight(1f),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
        )
        status?.let { (ok, msg) ->
            Text(msg, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                status = runCatching { onSave(text) }
                    .fold({ true to "Saved: $it" }, { false to (it.message ?: "Could not save") })
            }) { Text("Save") }
            if (bundled != null && onRevert != null) {
                OutlinedButton(onClick = {
                    onRevert()
                    text = bundled
                    status = true to "Back to the bundled layout"
                }) { Text("Revert") }
            }
            OutlinedButton(onClick = onClose) { Text("Close") }
        }
    }
}
