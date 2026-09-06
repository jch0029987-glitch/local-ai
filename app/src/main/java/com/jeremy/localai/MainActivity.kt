package com.jeremy.localai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
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
import com.jeremy.localai.engine.BrowserAccessServer
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.codeshipping.llamakotlin.LlamaModel
import org.json.JSONArray
import org.json.JSONObject
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

// --- Hugging Face Live Search Utility ---
data class HuggingFaceModelItem(
    val modelId: String,
    val downloads: Int,
    val downloadUrl: String
)

object HuggingFaceHub {
    private val client = OkHttpClient()

    suspend fun searchGgufModels(query: String = "instruct"): List<HuggingFaceModelItem> = withContext(Dispatchers.IO) {
        val url = "https://huggingface.co/api/models".toHttpUrl().newBuilder()
            .addQueryParameter("search", if (query.contains("GGUF", ignoreCase = true)) query else "$query GGUF")
            .addQueryParameter("sort", "downloads")
            .addQueryParameter("direction", "-1")
            .addQueryParameter("limit", "15")
            .build()

        val request = Request.Builder().url(url).build()
        val results = mutableListOf<HuggingFaceModelItem>()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val responseBody = response.body?.string() ?: return@withContext emptyList()
                val jsonArray = JSONArray(responseBody)

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val modelId = obj.getString("id")
                    val downloads = obj.optInt("downloads", 0)
                    val directUrl = "https://huggingface.co/$modelId/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"
                    results.add(HuggingFaceModelItem(modelId, downloads, directUrl))
                }
            }
        } catch (_: Exception) {}

        results
    }
}

// --- App Preferences for Onboarding & Setup ---
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
    object UpdateScreen : AppScreen("update_screen")
}

class MainActivity : ComponentActivity() {

    private var currentEngine: AiEngine? = null
    private var modelPath: String? = null
    private val database by lazy { AppDatabase.getDatabase(this) }
    private var webServer: BrowserAccessServer? = null

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

        try {
            webServer = BrowserAccessServer(8080).apply { start() }
        } catch (_: Exception) {}

        // Run Root Check on Startup via libsu
        lifecycleScope.launch(Dispatchers.IO) {
            val hasRoot = Shell.getShell().isRoot
            withContext(Dispatchers.Main) {
                if (hasRoot) {
                    statusText = "Status: Root access verified via libsu"
                } else {
                    statusText = "Status: Root access unavailable"
                }
            }
        }

        val defaultModelFile = File(filesDir, "imported_model.gguf")
        if (defaultModelFile.exists() && modelPath == null) {
            modelPath = defaultModelFile.absolutePath
            autoLoadStoredModel(defaultModelFile.absolutePath)
        }

        // Load sessions list reactively from Room
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

        // Load messages for current session dynamically from Room
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
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { statusText = "Status: Auto-load failed" }
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

                try { currentEngine?.close() } catch (_: Exception) {}

                if (isLiteRt) {
                    withContext(Dispatchers.Main) { statusText = "Loading LiteRT-LM Engine..." }
                    val liteRtEngine = LiteRtEngine(this@MainActivity)
                    liteRtEngine.loadModel(modelPath!!, options)
                    currentEngine = liteRtEngine
                    withContext(Dispatchers.Main) { statusText = "Status: LiteRT-LM Ready" }
                } else {
                    withContext(Dispatchers.Main) { statusText = "Loading GGUF Engine..." }
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
        webServer?.stop()
        try { currentEngine?.close() } catch (_: Exception) {}
    }
}

// --- Navigation & Router ---
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
                onNavigateToModelManager = { navController.navigate(AppScreen.ModelManager.route) },
                onNavigateToUpdate = { navController.navigate(AppScreen.UpdateScreen.route) },
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
                titleText = "Hugging Face Model Hub",
                subtitleText = "Search and download GGUF models directly from live repositories.",
                onDownloadComplete = { savedPath ->
                    onModelPathReady(savedPath)
                    navController.popBackStack()
                },
                onBackClicked = { navController.popBackStack() }
            )
        }
        composable(AppScreen.UpdateScreen.route) {
            UpdateScreen(
                onBackClicked = { navController.popBackStack() }
            )
        }
    }
}

// --- Dedicated Update Management Screen ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(onBackClicked: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var checkingStatus by remember { mutableStateOf("Tap below to check for updates.") }
    var updateAvailable by remember { mutableStateOf(false) }
    var remoteVersion by remember { mutableStateOf("") }
    var apkUrl by remember { mutableStateOf("") }
    var isChecking by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Updates") },
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
                    Text(text = "Current Status", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = checkingStatus, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
            }

            if (isDownloading) {
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = {
                    isChecking = true
                    checkingStatus = "Checking repository update.json..."
                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            val client = OkHttpClient()
                            val request = Request.Builder()
                                .url("https://raw.githubusercontent.com/jch0029987-glitch/local-ai/main/update.json")
                                .build()
                            
                            client.newCall(request).execute().use { response ->
                                if (response.isSuccessful) {
                                    val bodyStr = response.body?.string() ?: ""
                                    val json = JSONObject(bodyStr)
                                    
                                    remoteVersion = json.optString("versionName", json.optString("version", "1.0.0"))
                                    apkUrl = json.optString("downloadUrl", json.optString("apk", json.optString("zipUrl", "")))
                                    
                                    updateAvailable = true
                                    withContext(Dispatchers.Main) {
                                        checkingStatus = "Update available: v$remoteVersion"
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        checkingStatus = "Failed to fetch update info (Code: ${response.code})"
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                checkingStatus = "Error: ${e.localizedMessage}"
                            }
                        } finally {
                            isChecking = false
                        }
                    }
                },
                enabled = !isChecking && !isDownloading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (isChecking) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Check for Updates", style = MaterialTheme.typography.titleMedium)
                }
            }

            if (updateAvailable) {
                Button(
                    onClick = {
                        if (apkUrl.isNotBlank()) {
                            isDownloading = true
                            checkingStatus = "Downloading APK update..."
                            coroutineScope.launch(Dispatchers.IO) {
                                try {
                                    val client = OkHttpClient()
                                    val request = Request.Builder().url(apkUrl).build()
                                    val response = client.newCall(request).execute()
                                    if (response.isSuccessful) {
                                        val body = response.body
                                        if (body != null) {
                                            val apkFile = File(context.getExternalFilesDir(null), "update.apk")
                                            val totalBytes = body.contentLength()
                                            var downloaded = 0L

                                            body.byteStream().use { input ->
                                                FileOutputStream(apkFile).use { output ->
                                                    val buffer = ByteArray(8192)
                                                    var bytesRead: Int
                                                    while (input.read(buffer).also { bytesRead = it } != -1) {
                                                        output.write(buffer, 0, bytesRead)
                                                        downloaded += bytesRead
                                                        if (totalBytes > 0) {
                                                            downloadProgress = downloaded.toFloat() / totalBytes.toFloat()
                                                        }
                                                    }
                                                }
                                            }

                                            withContext(Dispatchers.Main) {
                                                checkingStatus = "Download complete. Launching installer..."
                                                isDownloading = false
                                                
                                                val uri: Uri = FileProvider.getUriForFile(
                                                    context,
                                                    "${context.packageName}.fileprovider",
                                                    apkFile
                                                )
                                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(uri, "application/vnd.android.package-archive")
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                context.startActivity(intent)
                                            }
                                        }
                                    } else {
                                        withContext(Dispatchers.Main) {
                                            isDownloading = false
                                            checkingStatus = "Download failed: ${response.code}"
                                        }
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        isDownloading = false
                                        checkingStatus = "Download error: ${e.localizedMessage}"
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
                    Text(text = "Download & Install APK", style = MaterialTheme.typography.titleMedium)
                }
            }
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

// --- Searchable Model Download Screen ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDownloadScreen(
    titleText: String = "Hugging Face Model Hub",
    subtitleText: String = "Search and download GGUF models directly from live repositories.",
    onDownloadComplete: (String) -> Unit,
    onBackClicked: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("Qwen 2.5") }
    var searchResults by remember { mutableStateOf<List<HuggingFaceModelItem>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    var selectedModel by remember { mutableStateOf<HuggingFaceModelItem?>(null) }
    var isDownloading by remember { mutableStateOf(false) }
    var progressPercent by remember { mutableStateOf(0f) }
    var progressDetailsText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        isSearching = true
        searchResults = HuggingFaceHub.searchGgufModels(searchQuery)
        isSearching = false
    }

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
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (onBackClicked == null) {
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search model (e.g. Llama, Phi, Qwen)") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        coroutineScope.launch {
                            isSearching = true
                            searchResults = HuggingFaceHub.searchGgufModels(searchQuery)
                            isSearching = false
                        }
                    },
                    modifier = Modifier.height(56.dp)
                ) {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                }
            }

            if (isDownloading) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Downloading: ${selectedModel?.modelId ?: "Model"}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        LinearProgressIndicator(
                            progress = { progressPercent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                        )
                        Text(
                            text = progressDetailsText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Start)
                )

                if (isSearching) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(searchResults) { item ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedModel = item
                                        isDownloading = true
                                        errorMessage = null
                                        coroutineScope.launch {
                                            ModelDownloader.downloadModel(
                                                context,
                                                item.downloadUrl,
                                                "imported_model.gguf"
                                            ).collect { state ->
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
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = item.modelId,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Downloads: ${item.downloads}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "Tap to Download",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
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
        }
    }
}

// --- Onboarding & Setup UI Screens ---
@Composable
fun OnboardingScreen(onFinished: (Boolean, String) -> Unit) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()

    var useOffline by remember { mutableStateOf(true) }
    var targetModelUrl by remember { mutableStateOf("https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf") }

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
                        description = "Run GGUF and LiteRT models natively utilizing your device's hardware acceleration without relying on cloud servers.",
                        icon = Icons.Default.CloudOff
                    )
                    1 -> OnboardingPageView(
                        title = "Zero Data Leakage",
                        description = "Your prompts, session data, and private context remain securely inside your hardware environment.",
                        icon = Icons.Default.Security
                    )
                    2 -> SetupConfigurationPageView(
                        useOffline = useOffline,
                        onOfflineChanged = { useOffline = it },
                        modelUrl = targetModelUrl,
                        onModelUrlChanged = { targetModelUrl = it }
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    modifier = Modifier.padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    repeat(3) { index ->
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
                        if (pagerState.currentPage < 2) {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        } else {
                            onFinished(useOffline, targetModelUrl)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text(text = if (pagerState.currentPage == 2) "Initialize App" else "Next")
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
fun SetupConfigurationPageView(
    useOffline: Boolean,
    onOfflineChanged: (Boolean) -> Unit,
    modelUrl: String,
    onModelUrlChanged: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Engine Setup Options",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Configure how your local runtime acquires model weights.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(24.dp))

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
                    Text(text = "Pure Offline Storage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(text = "I will manually manage my local GGUF/LiteRT files.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = useOffline, onCheckedChange = onOfflineChanged)
            }
        }

        if (!useOffline) {
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = modelUrl,
                onValueChange = onModelUrlChanged,
                label = { Text("Model Download URL (.gguf)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                maxLines = 3,
                shape = RoundedCornerShape(8.dp)
            )
        }
    }
}

@Composable
fun SplashScreen(onLoadingFinished: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(1800L)
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
                contentDescription = "Engine Loading",
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

// --- MainScreen UI & Drawer ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    status: String,
    sessions: List<ChatSession>,
    currentSessionId: Long?,
    messages: List<ChatMessage>,
    isGenerating: Boolean,
    onNavigateToModelManager: () -> Unit,
    onNavigateToUpdate: () -> Unit,
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
                    title = { Text("Local AI Hub") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    },
                    actions = {
                        IconButton(onClick = onNavigateToUpdate) {
                            Icon(Icons.Default.SystemUpdate, contentDescription = "Updates")
                        }
                        IconButton(onClick = onNavigateToModelManager) {
                            Icon(Icons.Default.Storage, contentDescription = "Model Manager")
                        }
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
                    .padding(16.dp)
            ) {
                Text(text = status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
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
