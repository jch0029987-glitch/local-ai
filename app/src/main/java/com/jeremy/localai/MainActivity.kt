package com.jeremy.localai

import android.content.Context
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.codeshipping.llamakotlin.LlamaModel
import java.io.File
import java.io.FileOutputStream
import java.text.DecimalFormat

// --- Background Model Downloader Utility & State ---
sealed class DownloadState {
    data class Progress(val progressBytes: Long, val totalBytes: Long, val percent: Float) : DownloadState()
    data class Success(val file: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

object ModelDownloader {
    private val client = OkHttpClient()

    fun downloadModel(context: Context, urlString: String, fileName: String): Flow<DownloadState> = flow {
        emit(DownloadState.Progress(0, 0, 0f))
        val destinationFile = File(context.filesDir, fileName)

        try {
            val request = Request.Builder().url(urlString).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                emit(DownloadState.Error("Server error code: ${response.code}"))
                return@flow
            }

            val body = response.body
            if (body == null) {
                emit(DownloadState.Error("Empty server response body"))
                return@flow
            }

            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            body.byteStream().use { inputStream ->
                FileOutputStream(destinationFile).use { outputStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        val percent = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes.toFloat()) * 100f else 0f
                        emit(DownloadState.Progress(downloadedBytes, totalBytes, percent))
                    }
                }
            }

            emit(DownloadState.Success(destinationFile))
        } catch (e: Exception) {
            emit(DownloadState.Error(e.localizedMessage ?: "Unknown download error"))
        }
    }.flowOn(Dispatchers.IO)

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val mb = bytes / (1024.0 * 1024.0)
        val gb = mb / 1024.0
        return if (gb >= 1.0) {
            DecimalFormat("#.##").format(gb) + " GB"
        } else {
            DecimalFormat("#.##").format(mb) + " MB"
        }
    }
}

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
    object ModelManager : AppScreen("model_manager")
    object ModelDownloaderHub : AppScreen("model_downloader_hub")
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
                        onTriggerFilePicker = { filePickerLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                        onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                        onNewChat = { createNewSession() },
                        onSelectSession = { currentSessionId = it },
                        onSendPrompt = { prompt -> runInference(prompt) },
                        onModelPathReady = { path -> autoLoadStoredModel(path) }
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
        modelPath = path
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

                try { currentEngine?.close() } catch (_: Exception) {}

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
    onTriggerFilePicker: () -> Unit,
    onOpenSettings: () -> Unit,
    onNewChat: () -> Unit,
    onSelectSession: (Long) -> Unit,
    onSendPrompt: (String) -> Unit,
    onModelPathReady: (String) -> Unit
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
                onFinished = { savedPath ->
                    prefs.hasSeenOnboarding = true
                    onModelPathReady(savedPath)
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
                onNavigateToModelManager = { navController.navigate(AppScreen.ModelManager.route) },
                onOpenSettings = onOpenSettings,
                onNewChat = onNewChat,
                onSelectSession = onSelectSession,
                onSendPrompt = onSendPrompt
            )
        }
        composable(AppScreen.ModelManager.route) {
            ModelManagerScreen(
                status = status,
                onSelectFileClicked = onTriggerFilePicker,
                onNavigateToDownloader = { navController.navigate(AppScreen.ModelDownloaderHub.route) },
                onBackClicked = { navController.popBackStack() }
            )
        }
        composable(AppScreen.ModelDownloaderHub.route) {
            ModelDownloadScreen(
                titleText = "Model Downloader",
                subtitleText = "Fetch optimized models directly from verified remote repositories.",
                onDownloadComplete = { savedPath ->
                    onModelPathReady(savedPath)
                    navController.popBackStack()
                },
                onBackClicked = { navController.popBackStack() }
            )
        }
    }
}

// --- Dedicated Model Management Screen ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerScreen(
    status: String,
    onSelectFileClicked: () -> Unit,
    onNavigateToDownloader: () -> Unit,
    onBackClicked: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model Management") },
                navigationIcon = {
                    IconButton(onClick = onBackClicked) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Current Runtime Status", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = status, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = onNavigateToDownloader,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.CloudDownload, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Download Model from Cloud Hub", style = MaterialTheme.typography.titleMedium)
            }

            OutlinedButton(
                onClick = onSelectFileClicked,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Browse Local Storage File", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

// --- Shared Reusable Model Download Screen ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDownloadScreen(
    titleText: String = "Download Model",
    subtitleText: String = "Select an optimized neural model to download directly to local storage.",
    onDownloadComplete: (String) -> Unit,
    onBackClicked: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val modelName = "Qwen 2.5 (1.5B Instruct GGUF)"
    val modelUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"
    val estimatedSize = "~986 MB"

    var isDownloading by remember { mutableStateOf(false) }
    var progressPercent by remember { mutableStateOf(0f) }
    var progressDetailsText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            if (onBackClicked != null) {
                TopAppBar(
                    title = { Text(titleText) },
                    navigationIcon = {
                        IconButton(onClick = onBackClicked) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (onBackClicked == null) {
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = subtitleText,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = modelName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Badge { Text(estimatedSize) }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Optimized for fast local mobile inference with full on-device intelligence.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (isDownloading) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { progressPercent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        shape = RoundedCornerShape(4.dp)
                    )
                    Text(
                        text = progressDetailsText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            errorMessage?.let { error ->
                Text(
                    text = "Error: $error",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }

            Button(
                onClick = {
                    isDownloading = true
                    errorMessage = null
                    coroutineScope.launch {
                        ModelDownloader.downloadModel(context, modelUrl, "imported_model.gguf").collect { state ->
                            when (state) {
                                is DownloadState.Progress -> {
                                    progressPercent = state.percent
                                    val downloadedFormatted = ModelDownloader.formatSize(state.progressBytes)
                                    val totalFormatted = ModelDownloader.formatSize(state.totalBytes)
                                    progressDetailsText = "Downloading... $downloadedFormatted / $totalFormatted (${state.percent.toInt()}%)"
                                }
                                is DownloadState.Success -> {
                                    isDownloading = false
                                    onDownloadComplete(state.file.absolutePath)
                                }
                                is DownloadState.Error -> {
                                    isDownloading = false
                                    errorMessage = state.message
                                }
                            }
                        }
                    }
                },
                enabled = !isDownloading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isDownloading) "Downloading Model..." else "Download & Initialize",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

// --- Intelligent Onboarding with Background Download Integration ---
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(onFinished: (String) -> Unit) {
    val pagerState = rememberPagerState(pageCount = { 4 })
    val coroutineScope = rememberCoroutineScope()

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
                        description = "Run powerful large language models locally on your device hardware without requiring an active cloud connection."
                    )
                    1 -> OnboardingPageView(
                        title = "Private & Secure",
                        description = "Your prompts, chat conversations, and data never leave your device storage. Complete total local privacy."
                    )
                    2 -> OnboardingPageView(
                        title = "High Performance",
                        description = "Optimized via native hardware execution layers to ensure fast inference speeds and low thermal footprint."
                    )
                    3 -> ModelDownloadScreen(
                        titleText = "Download Default Model",
                        subtitleText = "Fetch your initial local language model to complete setup.",
                        onDownloadComplete = { savedPath ->
                            onFinished(savedPath)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (pagerState.currentPage < 3) {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = "Next",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}

@Composable
fun OnboardingPageView(title: String, description: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.SmartToy,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
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
fun SplashScreen(onLoadingFinished: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(1200)
        onLoadingFinished()
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun MainScreen(
    status: String,
    sessions: List<ChatSession>,
    currentSessionId: Long?,
    messages: List<ChatMessage>,
    isGenerating: Boolean,
    onNavigateToModelManager: () -> Unit,
    onOpenSettings: () -> Unit,
    onNewChat: () -> Unit,
    onSelectSession: (Long) -> Unit,
    onSendPrompt: (String) -> Unit
) {
    var inputPrompt by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Local AI Hub") },
                actions = {
                    IconButton(onClick = onNavigateToModelManager) {
                        Icon(Icons.Default.Storage, contentDescription = "Model Manager")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
        ) {
            Text(text = status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { msg ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (msg.role == "user") 
                                MaterialTheme.colorScheme.surfaceVariant 
                            else 
                                MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = if (msg.role == "user") "You" else "Assistant",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = msg.content, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputPrompt,
                    onValueChange = { inputPrompt = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a prompt...") },
                    maxLines = 4
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (inputPrompt.isNotBlank() && !isGenerating) {
                            onSendPrompt(inputPrompt)
                            inputPrompt = ""
                        }
                    },
                    enabled = !isGenerating
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send")
                }
            }
        }
    }
}
