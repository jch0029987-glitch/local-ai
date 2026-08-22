package com.jeremy.localai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val prefs = getSharedPreferences("ai_settings", MODE_PRIVATE)
        var threads by mutableStateOf(prefs.getInt("threads", 4).toString())
        var temperature by mutableStateOf(prefs.getFloat("temperature", 0.7f).toString())

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.systemBars)
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("Inference Settings", style = MaterialTheme.typography.titleLarge)

                        OutlinedTextField(
                            value = threads,
                            onValueChange = { threads = it },
                            label = { Text("CPU Threads (1-8)") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = temperature,
                            onValueChange = { temperature = it },
                            label = { Text("Temperature (0.0 - 1.0)") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Button(
                            onClick = {
                                val t = threads.toIntOrNull() ?: 4
                                val temp = temperature.toFloatOrNull() ?: 0.7f
                                prefs.edit().putInt("threads", t).putFloat("temperature", temp).apply()
                                finish()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Save & Return")
                        }
                    }
                }
            }
        }
    }
}
