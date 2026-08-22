package com.jeremy.localai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.jeremy.localai.db.AppDatabase
import com.jeremy.localai.db.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.codeshipping.llamakotlin.LlamaModel
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {

    private var llamaModel: LlamaModel? = null
    private var modelPath: String? = null
    private val database by lazy { AppDatabase.getDatabase(this) }

    private var statusText by mutableStateOf("Status: Model Unloaded")
    private var isGenerating by mutableStateOf(false)
    private val messagesState = mutableStateOf<List<ChatMessage>>(emptyList())

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importModelFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Collect messages from Room DB reactively
        lifecycleScope.launch(Dispatchers.IO) {
            database.chatDao().getAllMessages().collectLatest { list ->
                messagesState.value = list
            }
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        status = statusText,
                        messages = messagesState.value,
                        isGenerating = isGenerating,
                        onSelectModel = { filePickerLauncher.launch(arrayOf("*/*")) },
                        onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                        onSendPrompt = { prompt -> runInference(prompt) }
                    )
                }
            }
        }
    }

    private fun importModelFile(uri: Uri) {
        statusText = "Importing model file..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val destinationFile = File(filesDir, "imported_model.gguf")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destinationFile).use { output -> input.copyTo(output) }
                }
                modelPath = destinationFile.absolutePath
                loadModelIntoEngine(modelPath!!)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText = "Import failed: ${e.localizedMessage}"
                }
            }
        }
    }

    private suspend fun loadModelIntoEngine(path: String) {
        try {
            try { llamaModel?.close() } catch (_: Exception) {}

            val prefs = getSharedPreferences("ai_settings", MODE_PRIVATE)
            val threads = prefs.getInt("threads", 4)
            val temperature = prefs.getFloat("temperature", 0.7f)

            llamaModel = LlamaModel.load(path) {
                contextSize = 2048
                this.threads = threads
                this.temperature = temperature
            }
            withContext(Dispatchers.Main) {
                statusText = "Status: Model Loaded & Ready"
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                statusText = "Engine load error: ${e.localizedMessage}"
            }
        }
    }

    private fun runInference(userInput: String) {
        if (userInput.isBlank() || llamaModel == null) return
        isGenerating = true
        statusText = "Status: Generating response..."

        lifecycleScope.launch(Dispatchers.IO) {
            database.chatDao().insertMessage(ChatMessage(role = "user", content = userInput))

            val promptBuilder = StringBuilder("<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n")
            promptBuilder.append("<|im_start|>user\n$userInput<|im_end|>\n<|im_start|>assistant\n")

            val responseBuilder = StringBuilder()
            try {
                llamaModel?.generateStream(promptBuilder.toString())?.collect { token ->
                    responseBuilder.append(token)
                }
                database.chatDao().insertMessage(ChatMessage(role = "assistant", content = responseBuilder.toString().trim()))
            } catch (e: Exception) {
                database.chatDao().insertMessage(ChatMessage(role = "assistant", content = "Error: ${e.localizedMessage}"))
            } finally {
                withContext(Dispatchers.Main) {
                    isGenerating = false
                    statusText = "Status: Model Loaded & Ready"
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { llamaModel?.close() } catch (_: Exception) {}
    }
}

@Composable
fun MainScreen(
    status: String,
    messages: List<ChatMessage>,
    isGenerating: Boolean,
    onSelectModel: () -> Unit,
    onOpenSettings: () -> Unit,
    onSendPrompt: (String) -> Unit
) {
    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(16.dp)
    ) {
        Text(text = status, style = MaterialTheme.typography.bodyMedium)
        
        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSelectModel, modifier = Modifier.weight(1f)) {
                Text("Select Model")
            }
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) {
                Text("Settings")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Chat message history list
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { msg ->
                val isUser = msg.role == "user"
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer 
                                         else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = if (isUser) "You" else "Assistant",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = msg.content, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a prompt...") },
                maxLines = 3
            )
            Button(
                onClick = {
                    if (textInput.isNotBlank()) {
                        onSendPrompt(textInput)
                        textInput = ""
                    }
                },
                enabled = !isGenerating && textInput.isNotBlank()
            ) {
                Text("Send")
            }
        }
    }
}
