package com.jeremy.localai

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
import kotlinx.coroutines.Dispatchers
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
    private lateinit var scrollView: ScrollView

    private var llamaModel: LlamaModel? = null
    private var modelPath: String? = null

    // Storage Access Framework file picker for GGUF weights
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selectedUri ->
            importModelFile(selectedUri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind UI elements from activity_main.xml
        statusTextView = findViewById(R.id.statusTextView)
        outputTextView = findViewById(R.id.outputTextView)
        promptEditText = findViewById(R.id.promptEditText)
        selectButton = findViewById(R.id.selectButton)
        sendButton = findViewById(R.id.sendButton)
        scrollView = findViewById(R.id.scrollView) // Note: wrap your output TextView in a ScrollView in XML if not already done

        // Handle system bar padding insets dynamically
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainLayout)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setupListeners()
    }

    private fun setupListeners() {
        selectButton.setOnClickListener {
            filePickerLauncher.launch(arrayOf("*/*"))
        }

        sendButton.setOnClickListener {
            val prompt = promptEditText.text.toString()
            if (prompt.isNotBlank() && llamaModel != null) {
                runInference(prompt)
                promptEditText.setText("") // Clear input field after sending
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
                    FileOutputStream(destinationFile).use { output ->
                        input.copyTo(output)
                    }
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
            // Close existing model instance if re-importing
            try { llamaModel?.close() } catch (_: Exception) {}

            llamaModel = LlamaModel.load(path) {
                contextSize = 2048
                threads = 4
                temperature = 0.7f
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

    private fun runInference(userInput: String) {
        outputTextView.text = ""
        sendButton.isEnabled = false
        selectButton.isEnabled = false
        statusTextView.text = "Status: Generating response..."

        // Wrap input inside Qwen chat format tags so it acts as an assistant conversation
        val formattedPrompt = "<|im_start|>user\n$userInput<|im_end|>\n<|im_start|>assistant\n"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                llamaModel?.generateStream(formattedPrompt)?.collect { token ->
                    withContext(Dispatchers.Main) {
                        outputTextView.append(token)
                        // Auto-scroll down to follow streamed tokens
                        scrollView.post {
                            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    outputTextView.append("\nError during generation: ${e.localizedMessage}")
                }
            } finally {
                withContext(Dispatchers.Main) {
                    statusTextView.text = "Status: Model Loaded & Ready"
                    sendButton.isEnabled = true
                    selectButton.isEnabled = true
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            llamaModel?.close()
        } catch (_: Exception) {}
    }
}
