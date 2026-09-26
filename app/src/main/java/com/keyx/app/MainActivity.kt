package com.keyx.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.keyx.app.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    HomeScreen()
                }
            }
        }
    }
}

@Composable
fun HomeScreen() {
    // The smoke gate installs this on a device, launches it, checks the process
    // is still alive four seconds later and screencaps it. Anything that
    // crashes on start is caught there and nowhere earlier.
    var entries by remember { mutableStateOf(listOf("Stamped from the ProjectForge android-compose template")) }

    Column(modifier = Modifier.padding(24.dp)) {
        Text(text = "KeyX", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "Replace this screen with the real one.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        Button(onClick = { entries = entries + "Entry ${entries.size + 1}" }) {
            Text("Add an entry")
        }
        LazyColumn(modifier = Modifier.padding(top = 16.dp)) {
            items(entries) { entry ->
                Text(text = entry, modifier = Modifier.padding(vertical = 6.dp))
            }
        }
    }
}
