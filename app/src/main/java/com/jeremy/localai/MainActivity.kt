package com.jeremy.localai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

class MainActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var outputTextView: TextView
    private lateinit var promptEditText: EditText
    private lateinit var selectButton: Button
    private lateinit var sendButton: Button
    private lateinit var settingsButton: Button
    private lateinit var scrollView: ScrollView

    private var llamaModel: LlamaModel? = null
    private var modelPath: String? = null
    private val database by lazy { AppDatabase.getDatabase(this) }

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importModelFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusTextView = findViewById(R.id.statusTextView)
        outputTextView = findViewById(R.id.outputTextView)
        promptEditText = findViewById(R.id.promptEditText)
        selectButton = findViewById(R.id.selectButton)
        sendButton = findViewById(R.id.sendButton)
        settingsButton = findViewById(R.id.settingsButton) // Add button to XML if desired
        scrollView = findViewById(R.id.scrollView)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainLayout)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setupListeners()
        observeDatabase()
    }

    private fun setupListeners() {
        selectButton.setOnClickListener { filePickerLauncher.launch(arrayOf("*/*")) }
        
        // Optional: If you add a settings button to activity_main.xml
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        sendButton.setOnClickListener {
            val prompt = promptEditText.text.toString()
            if (prompt.isNotBlank() && llamaModel != null) {
                promptEditText.setText("")
                runInferenceWithHistory(prompt)
            }
        }
    }

    private fun observeDatabase() {
        lifecycleScope.launch(Dispatchers.IO) {
            database.chatDao().getAllMessages().collectLatest { messages ->
                val sb = StringBuilder()
                for (msg in messages) {
                    val label = if (msg.role == "user") "User" else "Assistant"
                    sb.append("$label: ${msg.content}\n\n")
                }
                withContext(Dispatchers.Main) {
                    outputTextView.text = sb.toString()
                    scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
        }
    }

    private fun importModelFile(uri: Uri) {
        statusTextView.text = "Importing model file..."
        selectButton.isEnabled = false
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
                    statusTextView.text = "Import failed: ${e.localizedMessage}"
                    selectButton.isEnabled = true
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
                statusTextView.text = "Status: Model Loaded & Ready"
                sendButton.isEnabled = true
                selectButton.isEnabled = true
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                statusTextView.text = "Engine load error: ${e.localizedMessage}"
                selectButton.isEnabled = true
            }
        }
    }

    private fun runInferenceWithHistory(userInput: String) {
        sendButton.isEnabled = false
        statusTextView.text = "Status: Generating response..."

        lifecycleScope.launch(Dispatchers.IO) {
            // 1. Save user prompt to Room DB
            database.chatDao().insertMessage(ChatMessage(role = "user", content = userInput))

            // 2. Build full conversation transcript for Qwen
            val allMessages = database.chatDao() // We can query a list directly via a non-flow method if desired, or build it
            // For simplicity, let's construct the prompt payload:
            val promptBuilder = StringBuilder("<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n")
            
            // Reconstruct history formatting
            // (You can write a dao method to get a snapshot list of messages)
            promptBuilder.append("<|im_start|>user\n$userInput<|im_end|>\n<|im_start|>assistant\n")

            val fullPrompt = promptBuilder.toString()
            val responseBuilder = StringBuilder()

            try {
                llamaModel?.generateStream(fullPrompt)?.collect { token ->
                    responseBuilder.append(token)
                    withContext(Dispatchers.Main) {
                        // Live stream previewing can be handled here if preferred
                    }
                }
                // 3. Save completed assistant response to Room DB
                database.chatDao().insertMessage(ChatMessage(role = "assistant", content = responseBuilder.toString().trim()))
            } catch (e: Exception) {
                database.chatDao().insertMessage(ChatMessage(role = "assistant", content = "Error: ${e.localizedMessage}"))
            } finally {
                withContext(Dispatchers.Main) {
                    statusTextView.text = "Status: Model Loaded & Ready"
                    sendButton.isEnabled = true
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { llamaModel?.close() } catch (_: Exception) {}
    }
}
