package com.jeremy.localai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
        var topP by mutableStateOf(prefs.getFloat("top_p", 0.9f).toString())
        var contextSize by mutableStateOf(prefs.getInt("context_size", 2048).toString())
        var systemPrompt by mutableStateOf(prefs.getString("system_prompt", "You are a helpful assistant.") ?: "")

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.systemBars)
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("Inference Settings", style = MaterialTheme.typography.titleLarge)

                        OutlinedTextField(
                            value = threads,
                            onValueChange = { threads = it },
                            label = { Text("CPU Threads") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = temperature,
                            onValueChange = { temperature = it },
                            label = { Text("Temperature (e.g. 0.7)") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = topP,
                            onValueChange = { topP = it },
                            label = { Text("Top-P (e.g. 0.9)") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = contextSize,
                            onValueChange = { contextSize = it },
                            label = { Text("Context Size (e.g. 2048)") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = systemPrompt,
                            onValueChange = { systemPrompt = it },
                            label = { Text("System Prompt") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 4
                        )

                        Button(
                            onClick = {
                                prefs.edit()
                                    .putInt("threads", threads.toIntOrNull() ?: 4)
                                    .putFloat("temperature", temperature.toFloatOrNull() ?: 0.7f)
                                    .putFloat("top_p", topP.toFloatOrNull() ?: 0.9f)
                                    .putInt("context_size", contextSize.toIntOrNull() ?: 2048)
                                    .putString("system_prompt", systemPrompt)
                                    .apply()
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
