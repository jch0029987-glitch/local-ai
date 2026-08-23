package com.jeremy.localai.engine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class HfModelItem(val modelId: String, val downloads: Int)

@Composable
fun HfSearchDialog(
    onDismiss: () -> Unit,
    onModelSelected: (String, String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("litert-community") }
    var searchResults = remember { mutableStateListOf<HfModelItem>() }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    var selectedRepo by remember { mutableStateOf<String?>(null) }
    var fileNameInput by remember { mutableStateOf("model.litertlm") }
    
    val scope = rememberCoroutineScope()

    fun performSearch(query: String) {
        isLoading = true
        errorMessage = null
        scope.launch(Dispatchers.IO) {
            try {
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val url = URL("https://huggingface.co/api/models?search=$encodedQuery&limit=15")
                val connection = url.openConnection() as HttpsURLConnection
                connection.requestMethod = "GET"
                
                val responseString = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(responseString)
                
                val list = mutableListOf<HfModelItem>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val id = obj.optString("id", "")
                    val downloads = obj.optInt("downloads", 0)
                    if (id.isNotBlank()) {
                        list.add(HfModelItem(id, downloads))
                    }
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (selectedRepo == null) "Search Hugging Face Hub" else "Configure File") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().height(350.dp)) {
                if (selectedRepo == null) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("Search models...") },
                            singleLine = true
                        )
                        Button(
                            onClick = { performSearch(searchQuery) },
                            modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically)
                        ) {
                            Text("Search")
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))

                    if (isLoading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else if (errorMessage != null) {
                        Text(text = "Error: $errorMessage", color = MaterialTheme.colorScheme.error)
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(searchResults) { model ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable { selectedRepo = model.modelId },
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(text = model.modelId, style = MaterialTheme.typography.titleSmall)
                                        Text(text = "Downloads: ${model.downloads}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Text("Selected Repo:", style = MaterialTheme.typography.labelMedium)
                    Text(text = selectedRepo!!, style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = fileNameInput,
                        onValueChange = { fileNameInput = it },
                        label = { Text("File Name in Repo (.litertlm)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            if (selectedRepo != null) {
                Button(onClick = {
                    onModelSelected(selectedRepo!!, fileNameInput)
                    onDismiss()
                }) {
                    Text("Start Download")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                if (selectedRepo != null) selectedRepo = null else onDismiss()
            }) {
                Text(if (selectedRepo != null) "Back" else "Cancel")
            }
        }
    )
}
