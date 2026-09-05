package com.jeremy.localai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.core.content.FileProvider
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

// --- App Update Checker Utility ---
data class UpdateInfo(val versionCode: Int, val versionName: String, val downloadUrl: String, val releaseNotes: String)

object UpdateChecker {
    private val client = OkHttpClient()

    suspend fun fetchLatestVersion(jsonUrl: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(jsonUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                UpdateInfo(
                    versionCode = json.getInt("versionCode"),
                    versionName = json.getString("versionName"),
                    downloadUrl = json.getString("downloadUrl"),
                    releaseNotes = json.getString("releaseNotes")
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("local_ai_prefs", Context.MODE_PRIVATE)

    var hasSeenOnboarding: Boolean
        get() = prefs.getBoolean("has_seen_onboarding", false)
        set(value) = prefs.edit().putBoolean("has_seen_onboarding", value).apply()
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

    private var statusText by mutableStateOf("Status: Model Unloaded")
    private var isGenerating by mutableStateOf(false)
    private var isRootGranted by mutableStateOf(false)
    
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

        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10)
        )
        checkRootAccess()

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
                        isRootGranted = isRootGranted,
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

    private fun checkRootAccess() {
        try {
            isRootGranted = Shell.isRootPermissionGranted() || Shell.getShell().isRoot
        } catch (_: Exception) {
            isRootGranted = false
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
    isRootGranted: Boolean,
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
                    if (savedPath != null) onModelPathReady(savedPath)
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
                isRootGranted = isRootGranted,
                onNavigateToModelManager = { navController.navigate(AppScreen.ModelManager.route) },
                onNavigateToUpdates = { navController.navigate(AppScreen.UpdateScreen.route) },
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
                currentVersionCode = 1,
                updateJsonUrl = "https://raw.githubusercontent.com/jch0029987-glitch/local-ai/ui-rewrite/update.json",
                onBackClicked = { navController.popBackStack() }
            )
        }
    }
}

// --- Software Update Screen ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(
    currentVersionCode: Int,
    updateJsonUrl: String,
    onBackClicked: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isDownloading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Checking for updates...") }
    var progressText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val info = UpdateChecker.fetchLatestVersion(updateJsonUrl)
        updateInfo = info
        isLoading = false
        statusText = if (info != null && info.versionCode > currentVersionCode) {
            "New update available!"
        } else {
            "You are running the latest version."
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Software Update") },
                navigationIcon = {
                    IconButton(onClick = onBackClicked) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                updateInfo?.let { info ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Latest Version: ${info.versionName} (${info.versionCode})",
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Release Notes:\n${info.releaseNotes}",
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
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(text = progressText, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    if (info.versionCode > currentVersionCode && !isDownloading) {
                        Button(
                            onClick = {
                                if (!context.packageManager.canRequestPackageInstalls()) {
                                    val permissionIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(permissionIntent)
                                    return@Button
                                }

                                isDownloading = true
                                progressText = "Downloading update APK..."

                                coroutineScope.launch(Dispatchers.IO) {
                                    try {
                                        val client = OkHttpClient()
                                        val request = Request.Builder().url(info.downloadUrl).build()
                                        val response = client.newCall(request).execute()

                                        val apkFile = File(context.cacheDir, "update.apk")
                                        response.body?.byteStream()?.use { input ->
                                            FileOutputStream(apkFile).use { output ->
                                                input.copyTo(output)
                                            }
                                        }

                                        val apkUri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            apkFile
                                        )

                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(apkUri, "application/vnd.android.package-archive")
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }

                                        withContext(Dispatchers.Main) {
                                            isDownloading = false
                                            progressText = "Launching installer..."
                                            context.startActivity(intent)
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            isDownloading = false
                                            progressText = "Download failed: ${e.localizedMessage}"
                                        }
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("Download and Install Update", style = MaterialTheme.typography.titleMedium)
                        }
                    }
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
                    text = "Select a model to download:",
                    style = MaterialTheme.typography.labelLarge,
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

// --- Intelligent Onboarding with Optional Download Integration ---
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(onFinished: (String?) -> Unit) {
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
                    3 -> Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        ModelDownloadScreen(
                            titleText = "Download Default Model (Optional)",
                            subtitleText = "Fetch your initial local language model now, or skip and load one later.",
                            onDownloadComplete = { savedPath ->
                                onFinished(savedPath)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (pagerState.currentPage == 3) {
                    OutlinedButton(
                        onClick = { onFinished(null) },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Skip for Now")
                    }
                }

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
                        Text("Next", style = MaterialTheme.typography.titleMedium)
                    }
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
    isRootGranted: Boolean,
    onNavigateToModelManager: () -> Unit,
    onNavigateToUpdates: () -> Unit,
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
                    IconButton(onClick = onNavigateToUpdates) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = "Check Updates")
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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Badge(
                    containerColor = if (isRootGranted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(text = if (isRootGranted) "Root: Active" else "Root: Unprivileged")
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
