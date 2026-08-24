package com.jeremy.localai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jeremy.localai.db.AppDatabase
import com.jeremy.localai.db.ChatMessage
import com.jeremy.localai.db.ChatSession
import com.jeremy.localai.engine.AiEngine
import com.jeremy.localai.engine.EngineOptions
import com.jeremy.localai.engine.LiteRtEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.codeshipping.llamakotlin.LlamaModel
import java.io.File
import java.io.FileOutputStream
import java.net.URL

// --- App Preferences for Onboarding & Setup State ---
class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("local_ai_prefs", Context.MODE_PRIVATE)

    var hasSeenOnboarding: Boolean
        get() = prefs.getBoolean("has_seen_onboarding", false)
        set(value) = prefs.edit().putBoolean("has_seen_onboarding", value).apply()

    var useOfflineMode: Boolean
        get() = prefs.getBoolean("use_offline_mode", true)
        set(value) = prefs.edit().putBoolean("use_offline_mode", value).apply()
        
    var selectedModelUrl: String
        get() = prefs.getString("selected_model_url", "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf") ?: ""
        set(value) = prefs.edit().putString("selected_model_url", value).apply()
}

sealed class AppScreen(val route: String) {
    object Splash : AppScreen("splash")
    object Onboarding : AppScreen("onboarding")
    object MainHub : AppScreen("main_hub")
}

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
        uri?.let { 
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            importModelFile(it) 
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val defaultModelFile = File(filesDir, "imported_model.gguf")
        if (defaultModelFile.exists() && modelPath == null) {
            modelPath = defaultModelFile.absolutePath
            autoLoadStoredModel(defaultModelFile.absolutePath)
        }

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
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRootNavigation(
                        status = statusText,
                        sessions = sessionsState.value,
                        currentSessionId = currentSessionId,
                        messages = messagesState.value,
                        isGenerating = isGenerating,
                        onSelectModel = { filePickerLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
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

    private fun autoLoadStoredModel(path: String) {
        statusText = "Status: Loading stored model..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val prefs = getSharedPreferences("ai_settings", MODE_PRIVATE)
                val options = EngineOptions(
                    threads = prefs.getInt("threads", 4),
                    contextSize = prefs.getInt("context_size", 2048),
                    temperature = prefs.getFloat("temperature", 0.7f)
                )

                class GgufEngineWrapper : AiEngine {
                    private var model: LlamaModel? = null
                    override suspend fun loadModel(path: String, options: EngineOptions) {
                        model = LlamaModel.load(path) {
                            contextSize = options.contextSize
                            threads = options.threads
                            temperature = options.temperature
                        }
                    }
                    override fun generateStream(prompt: String) = flow {
                        model?.generateStream(prompt)?.collect { token -> emit(token) }
                    }
                    override fun close() { model?.close() }
                }

                val ggufEngine = GgufEngineWrapper()
                ggufEngine.loadModel(path, options)
                currentEngine = ggufEngine
                withContext(Dispatchers.Main) { statusText = "Status: GGUF Model Ready" }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { statusText = "Status: Auto-load failed" }
            }
        }
    }

    private fun importModelFile(uri: Uri) {
        statusText = "Status: Importing file..."
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

                try { currentEngine?.close() } catch (_: Exception) {}

                if (isLiteRt) {
                    withContext(Dispatchers.Main) { statusText = "Status: Loading LiteRT-LM..." }
                    val liteRtEngine = LiteRtEngine(this@MainActivity)
                    liteRtEngine.loadModel(modelPath!!, options)
                    currentEngine = liteRtEngine
                    withContext(Dispatchers.Main) { statusText = "Status: LiteRT-LM Ready" }
                } else {
                    withContext(Dispatchers.Main) { statusText = "Status: Loading GGUF Model..." }
                    class GgufEngineWrapper : AiEngine {
                        private var model: LlamaModel? = null
                        override suspend fun loadModel(path: String, options: EngineOptions) {
                            model = LlamaModel.load(path) {
                                contextSize = options.contextSize
                                threads = options.threads
                                temperature = options.temperature
                            }
                        }
                        override fun generateStream(prompt: String) = flow {
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
                    statusText = "Status: Load failed (${e.localizedMessage ?: "Unknown"})"
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

// --- App Root Navigation Router ---
@Composable
fun AppRootNavigation(
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
    val context = LocalContext.current
    val navController = rememberNavController()
    val prefs = remember { AppPreferences(context) }

    val startDestination = if (!prefs.hasSeenOnboarding) {
        AppScreen.Onboarding.route
    } else {
        AppScreen.Splash.route
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(AppScreen.Onboarding.route) {
            OnboardingScreen(
                onFinished = { offlineSelected, modelUrl ->
                    prefs.hasSeenOnboarding = true
                    prefs.useOfflineMode = offlineSelected
                    prefs.selectedModelUrl = modelUrl
                    navController.navigate(AppScreen.MainHub.route) {
                        popUpTo(AppScreen.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }
        composable(AppScreen.Splash.route) {
            SplashScreen(
                onLoadingFinished = {
                    navController.navigate(AppScreen.MainHub.route) {
                        popUpTo(AppScreen.Splash.route) { inclusive = true }
                    }
                }
            )
        }
        composable(AppScreen.MainHub.route) {
            MainScreen(
                status = status,
                sessions = sessions,
                currentSessionId = currentSessionId,
                messages = messages,
                isGenerating = isGenerating,
                onSelectModel = onSelectModel,
                onOpenSettings = onOpenSettings,
                onNewChat = onNewChat,
                onSelectSession = onSelectSession,
                onSendPrompt = onSendPrompt
            )
        }
    }
}

// --- Intelligent Onboarding with Background Download Integration ---
@Composable
fun OnboardingScreen(onFinished: (Boolean, String) -> Unit) {
    val pagerState = rememberPagerState(pageCount = { 4 })
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    var useOffline by remember { mutableStateOf(true) }
    var targetModelUrl by remember { mutableStateOf("https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf") }
    
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadStatusText by remember { mutableStateOf("") }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { page ->
                when (page) {
                    0 -> OnboardingPageView(
                        title = "100% Offline AI Execution",
                        description = "Run GGUF and LiteRT models natively utilizing hardware acceleration without cloud servers.",
                        icon = Icons.Default.CloudOff
                    )
                    1 -> OnboardingPageView(
                        title = "Zero Data Leakage",
                        description = "Your prompts, session data, and custom setups remain securely contained on-device.",
                        icon = Icons.Default.Security
                    )
                    2 -> ModelImportGuidePageView()
                    3 -> SetupConfigurationPageView(
                        useOffline = useOffline,
                        onOfflineChanged = { useOffline = it },
                        modelUrl = targetModelUrl,
                        onModelUrlChanged = { targetModelUrl = it },
                        isDownloading = isDownloading,
                        downloadProgress = downloadProgress,
                        downloadStatusText = downloadStatusText
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    modifier = Modifier.padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    repeat(4) { index ->
                        Box(
                            modifier = Modifier
                                .padding(4.dp)
                                .size(if (pagerState.currentPage == index) 12.dp else 8.dp)
                                .background(
                                    color = if (pagerState.currentPage == index) 
                                        MaterialTheme.colorScheme.primary 
                                    else 
                                        MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape
                                )
                        )
                    }
                }

                Button(
                    onClick = {
                        if (pagerState.currentPage < 3) {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        } else {
                            if (!useOffline && !isDownloading) {
                                isDownloading = true
                                downloadStatusText = "Downloading preset model..."
                                coroutineScope.launch(Dispatchers.IO) {
                                    try {
                                        val url = URL(targetModelUrl)
                                        val connection = url.openConnection()
                                        connection.connect()
                                        val fileSize = connection.contentLength.toFloat()
                                        
                                        val destination = File(context.filesDir, "imported_model.gguf")
                                        url.openStream().use { input ->
                                            FileOutputStream(destination).use { output ->
                                                val buffer = ByteArray(8192)
                                                var bytesRead: Int
                                                var totalBytesRead = 0f
                                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                                    output.write(buffer, 0, bytesRead)
                                                    totalBytesRead += bytesRead
                                                    if (fileSize > 0) {
                                                        downloadProgress = totalBytesRead / fileSize
                                                    }
                                                }
                                            }
                                        }
                                        withContext(Dispatchers.Main) {
                                            isDownloading = false
                                            onFinished(useOffline, targetModelUrl)
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            isDownloading = false
                                            downloadStatusText = "Download error: ${e.localizedMessage}"
                                        }
                                    }
                                }
                            } else {
                                onFinished(useOffline, targetModelUrl)
                            }
                        }
                    },
                    enabled = !isDownloading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text(text = if (pagerState.currentPage == 3) (if (isDownloading) "Downloading..." else "Initialize App") else "Next")
                }
            }
        }
    }
}

@Composable
fun OnboardingPageView(title: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(110.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ModelImportGuidePageView() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "How to Get Models",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "LocalAI supports standard GGUF and LiteRT-LM format files.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(20.dp))

        GuideStepCard(
            step = "1",
            title = "Download from Hugging Face",
            description = "Grab any mobile-optimized GGUF model file using your browser."
        )
        Spacer(modifier = Modifier.height(10.dp))
        GuideStepCard(
            step = "2",
            title = "Use In-App File Picker",
            description = "Tap 'Select Model' on the dashboard anytime to load your `.gguf` file."
        )
    }
}

@Composable
fun GuideStepCard(step: String, title: String, description: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(text = step, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun SetupConfigurationPageView(
    useOffline: Boolean,
    onOfflineChanged: (Boolean) -> Unit,
    modelUrl: String,
    onModelUrlChanged: (String) -> Unit,
    isDownloading: Boolean,
    downloadProgress: Float,
    downloadStatusText: String
) {
    val presetModels = listOf(
        "Qwen 2.5 (1.5B Instruct)" to "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
        "Llama 3.2 (1B Instruct)" to "https://huggingface.co/unsloth/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf"
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Engine Setup & Preset Download",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Select pure offline file-picker mode or download a Hugging Face preset automatically.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Pure Offline File Picker", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(text = "Manually pick files from device storage.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = useOffline, onCheckedChange = onOfflineChanged)
            }
        }

        if (!useOffline) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "Select Preset Model URL:", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(6.dp))

            presetModels.forEach { (name, url) ->
                val isSelected = modelUrl == url
                OutlinedCard(
                    onClick = { onModelUrlChanged(url) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(text = name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text(text = url, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (isDownloading) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${(downloadProgress * 100).toInt()}% - $downloadStatusText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            } else if (downloadStatusText.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = downloadStatusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun SplashScreen(onLoadingFinished: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(1200L)
        onLoadingFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Memory,
                contentDescription = "Loading",
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Initializing Neural Runtimes...",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(16.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(30.dp),
                strokeWidth = 3.dp
            )
        }
    }
}

// --- Professional Chat Interface & Drawer ---
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

    LaunchedEffect(messages.size, isGenerating) {
        if (messages.isNotEmpty() || isGenerating) {
            listState.animateScrollToItem(if (isGenerating) messages.size else maxOf(0, messages.size - 1))
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
                            label = { Text(session.title, maxLines = 1) },
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
                    },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp)
            ) {
                // Status Bar & Model Picker Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))

                    // Material3 OutlinedButton guarantees proper touch event capture 
                    // and bypasses parent drawer/gesture interceptor issues.
                    OutlinedButton(
                        onClick = onSelectModel,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 1.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen, 
                            contentDescription = null, 
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Select Model",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))

                // Professional Chat Bubbles Feed
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(messages) { msg ->
                        val isUser = msg.role == "user"
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
                        ) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isUser) 
                                        MaterialTheme.colorScheme.primaryContainer 
                                    else 
                                        MaterialTheme.colorScheme.surfaceVariant
                                ),
                                shape = RoundedCornerShape(
                                    topStart = 16.dp,
                                    topEnd = 16.dp,
                                    bottomStart = if (isUser) 16.dp else 4.dp,
                                    bottomEnd = if (isUser) 4.dp else 16.dp
                                ),
                                modifier = Modifier.widthIn(max = 300.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = if (isUser) "You" else "Assistant",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = msg.content,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }

                    // Pulsing Typing Indicator During Token Stream Generation
                    if (isGenerating) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                    shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = "Generating response...",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Chat Input and Action Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Type a message...") },
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp)
                    )
                    IconButton(
                        onClick = {
                            if (textInput.isNotBlank()) {
                                onSendPrompt(textInput)
                                textInput = ""
                            }
                        },
                        enabled = !isGenerating && textInput.isNotBlank(),
                        modifier = Modifier
                            .size(50.dp)
                            .background(
                                color = if (!isGenerating && textInput.isNotBlank()) 
                                    MaterialTheme.colorScheme.primary 
                                else 
                                    MaterialTheme.colorScheme.surfaceVariant,
                                shape = CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Send",
                            tint = if (!isGenerating && textInput.isNotBlank()) 
                                MaterialTheme.colorScheme.onPrimary 
                            else 
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
