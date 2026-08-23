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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.jeremy.localai.db.AppDatabase
import com.jeremy.localai.db.ChatMessage
import com.jeremy.localai.db.ChatSession
import com.jeremy.localai.engine.AiEngine
import com.jeremy.localai.engine.EngineOptions
import com.jeremy.localai.engine.LiteRtEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.codeshipping.llamakotlin.LlamaModel
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {

    private var currentEngine: AiEngine? = null
    private var modelPath: String? = null
    private val database by lazy { AppDatabase.getDatabase(this) }

    private var statusText by mutableStateOf("Status: Model Unloaded")
    private var isGenerating by mutableStateOf(false)
    
    private var sessionsState = mutableStateOf<List<ChatSession>>(emptyList())
    private var currentSessionId by mutableStateOf<Long?>(null)
    private var messagesState = mutableStateOf<List<ChatMessage>>(emptyList())

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importModelFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load sessions list reactively
        lifecycleScope.launch(Dispatchers.IO) {
            database.chatDao().getAllSessions().collectLatest { sessions ->
                sessionsState.value = sessions
                if (currentSessionId == null && sessions.isNotEmpty()) {
                    currentSessionId = sessions.first().id
                } else if (currentSessionId == null && sessions.isEmpty()) {
                    val newId = database.chatDao().insertSession(ChatSession(title = "New Chat"))
                    currentSessionId = newId
                }
            }
        }

        // Load messages for current session dynamically
        lifecycleScope.launch(Dispatchers.IO) {
            snapshotFlow { currentSessionId }.collectLatest { sessionId ->
                if (sessionId != null) {
                    database.chatDao().getMessagesForSession(sessionId).collectLatest { msgs ->
                        messagesState.value = msgs
                    }
                }
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
                        sessions = sessionsState.value,
                        currentSessionId = currentSessionId,
                        messages = messagesState.value,
                        isGenerating = isGenerating,
                        onSelectModel = { filePickerLauncher.launch(arrayOf("*/*")) },
                        onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                        onNewChat = { createNewSession() },
                        onSelectSession = { currentSessionId = it },
                        onSendPrompt = { prompt -> runInference(prompt) }
                    )
                }
            }
        }
    }

    private fun createNewSession() {
        lifecycleScope.launch(Dispatchers.IO) {
            val titleText = "Chat ${System.currentTimeMillis().toString().takeLast(4)}"
            val newId = database.chatDao().insertSession(ChatSession(title = titleText))
            withContext(Dispatchers.Main) {
                currentSessionId = newId
            }
        }
    }

    private fun importModelFile(uri: Uri) {
        statusText = "Importing model file..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pathString = uri.toString()
                val isLiteRt = pathString.endsWith(".litertlm", ignoreCase = true) || 
                               pathString.endsWith(".tflite", ignoreCase = true)
                
                val fileName = if (isLiteRt) "imported_model.litertlm" else "imported_model.gguf"
                val destinationFile = File(filesDir, fileName)
                
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destinationFile).use { output -> input.copyTo(output) }
                }
                modelPath = destinationFile.absolutePath

                val prefs = getSharedPreferences("ai_settings", MODE_PRIVATE)
                val options = EngineOptions(
                    threads = prefs.getInt("threads", 4),
                    contextSize = prefs.getInt("context_size", 2048),
                    temperature = prefs.getFloat("temperature", 0.7f)
                )

                // Close existing engine cleanly
                try { currentEngine?.close() } catch (_: Exception) {}

                if (isLiteRt) {
                    withContext(Dispatchers.Main) { statusText = "Loading LiteRT-LM Engine..." }
                    val liteRtEngine = LiteRtEngine(this@MainActivity)
                    liteRtEngine.loadModel(modelPath!!, options)
                    currentEngine = liteRtEngine
                    withContext(Dispatchers.Main) { statusText = "Status: LiteRT-LM Ready" }
                } else {
                    withContext(Dispatchers.Main) { statusText = "Loading GGUF Engine..." }
                    // Wrap existing LlamaModel into AiEngine interface inline
                    class GgufEngineWrapper : AiEngine {
                        private var model: LlamaModel? = null
                        override suspend fun loadModel(path: String, options: EngineOptions) {
                            model = LlamaModel.load(path) {
                                contextSize = options.contextSize
                                threads = options.threads
                                temperature = options.temperature
                            }
                        }
                        override fun generateStream(prompt: String) = kotlinx.coroutines.flow.flow {
                            model?.generateStream(prompt)?.collect { token -> emit(token) }
                        }
                        override fun close() { model?.close() }
                    }

                    val ggufEngine = GgufEngineWrapper()
                    ggufEngine.loadModel(modelPath!!, options)
                    currentEngine = ggufEngine
                    withContext(Dispatchers.Main) { statusText = "Status: GGUF Model Ready" }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText = "Load failed: ${e.localizedMessage}"
                }
            }
        }
    }

    private fun runInference(userInput: String) {
        val sessionId = currentSessionId ?: return
        val engine = currentEngine
        if (userInput.isBlank() || engine == null) return
        
        isGenerating = true
        statusText = "Status: Generating response..."

        lifecycleScope.launch(Dispatchers.IO) {
            database.chatDao().insertMessage(ChatMessage(sessionId = sessionId, role = "user", content = userInput))

            val prefs = getSharedPreferences("ai_settings", MODE_PRIVATE)
            val systemPrompt = prefs.getString("system_prompt", "You are a helpful assistant.")

            val promptBuilder = StringBuilder("<|im_start|>system\n$systemPrompt<|im_end|>\n")
            promptBuilder.append("<|im_start|>user\n$userInput<|im_end|>\n<|im_start|>assistant\n")

            val responseBuilder = StringBuilder()
            try {
                engine.generateStream(promptBuilder.toString()).collect { token ->
                    responseBuilder.append(token)
                }
                database.chatDao().insertMessage(ChatMessage(sessionId = sessionId, role = "assistant", content = responseBuilder.toString().trim()))
            } catch (e: Exception) {
                database.chatDao().insertMessage(ChatMessage(sessionId = sessionId, role = "assistant", content = "Error: ${e.localizedMessage}"))
            } finally {
                withContext(Dispatchers.Main) {
                    isGenerating = false
                    statusText = "Status: Model Ready"
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { currentEngine?.close() } catch (_: Exception) {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    status: String,
    sessions: List<ChatSession>,
    currentSessionId: Long?,
    messages: List<ChatMessage>,
    isGenerating: Boolean,
    onSelectModel: () -> Unit,
    onOpenSettings: () -> Unit,
    onNewChat: () -> Unit,
    onSelectSession: (Long) -> Unit,
    onSendPrompt: (String) -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Chat History", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                Button(onClick = onNewChat, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text("+ New Chat")
                }
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(sessions) { session ->
                        NavigationDrawerItem(
                            label = { Text(session.title) },
                            selected = session.id == currentSessionId,
                            onClick = {
                                onSelectSession(session.id)
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Local AI") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
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
    }
}
