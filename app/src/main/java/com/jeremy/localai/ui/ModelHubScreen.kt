package com.jeremy.localai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class HfModelItem(val modelId: String, val downloads: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelHubScreen(
    appFilesDir: File,
    onBack: () -> Unit,
    onModelReady: (String, Boolean) -> Unit // path, isLiteRt
) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = HF Search, 1 = Local Files
    var searchQuery by remember { mutableStateOf("litert-community") }
    var searchResults = remember { mutableStateListOf<HfModelItem>() }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // Download state
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadStatusText by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    fun performSearch(query: String) {
        isLoading = true
        errorMessage = null
        scope.launch(Dispatchers.IO) {
            try {
                val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                val url = URL("https://huggingface.co/api/models?search=$encoded&limit=20")
                val conn = url.openConnection() as HttpsURLConnection
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(response)
                
                val list = mutableListOf<HfModelItem>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val id = obj.optString("id", "")
                    val dls = obj.optInt("downloads", 0)
                    if (id.isNotBlank()) list.add(HfModelItem(id, dls))
                }

                withContext(Dispatchers.Main) {
                    searchResults.clear()
                    searchResults.addAll(list)
                    isLoading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorMessage = e.localizedMessage
                    isLoading = false
                }
            }
        }
    }

    fun downloadModel(repoId: String, fileName: String) {
        isDownloading = true
        downloadProgress = 0f
        downloadStatusText = "Connecting to Hugging Face..."

        scope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://huggingface.co/$repoId/resolve/main/$fileName")
                val conn = url.openConnection() as HttpsURLConnection
                conn.connect()
                val fileLength = conn.contentLength
                val targetFile = File(appFilesDir, "hf_${System.currentTimeMillis()}.litertlm")

                conn.inputStream.use { input ->
                    targetFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        var total = 0L
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            total += read
                            if (fileLength > 0) {
                                downloadProgress = total.toFloat() / fileLength.toFloat()
                                val percent = (total * 100) / fileLength
                                withContext(Dispatchers.Main) {
                                    downloadStatusText = "Downloading: $percent% (${total / 1024 / 1024} MB)"
                                }
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    isDownloading = false
                    onModelReady(targetFile.absolutePath, true)
                    onBack()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    downloadStatusText = "Error: ${e.localizedMessage}"
                    isDownloading = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model Hub & Downloader") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Hugging Face Hub") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Local Files") })
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (isDownloading) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(downloadStatusText, style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { downloadProgress }, modifier = Modifier.fillMaxWidth())
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (selectedTab == 0) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Search HF models...") },
                        singleLine = true
                    )
                    Button(onClick = { performSearch(searchQuery) }, modifier = Modifier.align(Alignment.CenterVertically)) {
                        Icon(Icons.Default.Search, contentDescription = null)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (errorMessage != null) {
                    Text("Error: $errorMessage", color = MaterialTheme.colorScheme.error)
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(searchResults) { model ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(model.modelId, style = MaterialTheme.typography.titleSmall)
                                    Text("Downloads: ${model.downloads}", style = MaterialTheme.typography.bodySmall)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(
                                        onClick = { downloadModel(model.modelId, "model.litertlm") },
                                        enabled = !isDownloading
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = null)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Download default .litertlm")
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Local files tab showing already downloaded or imported files in filesDir
                val localFiles = appFilesDir.listFiles()?.filter { 
                    it.name.endsWith(".litertlm") || it.name.endsWith(".gguf") || it.name.endsWith(".tflite") 
                } ?: emptyList()

                if (localFiles.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No local models found. Download one or import via file picker.", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(localFiles) { file ->
                            Card(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(file.name, style = MaterialTheme.typography.titleMedium)
                                        Text("Size: ${file.length() / 1024 / 1024} MB", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Button(onClick = {
                                        onModelReady(file.absolutePath, file.name.endsWith(".litertlm") || file.name.endsWith(".tflite"))
                                        onBack()
                                    }) {
                                        Text("Load")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
