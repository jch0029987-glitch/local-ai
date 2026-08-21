package com.jeremy.localai

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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

    private var llamaModel: LlamaModel? = null
    private var modelPath: String? = null

    // File picker launcher for importing local GGUF models
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selectedUri ->
            importModelFile(selectedUri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createProgrammaticLayout())

        setupListeners()
    }

    private fun createProgrammaticLayout(): android.view.View {
        // Simple linear layout container for quick UI construction
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        statusTextView = TextView(this).apply {
            text = getString(R.string.model_status_unloaded)
            textSize = 16f
            setPadding(0, 0, 0, 16)
        }

        selectButton = Button(this).apply {
            text = getString(R.string.select_model_btn)
        }

        promptEditText = EditText(this).apply {
            hint = "Enter your prompt here..."
            setPadding(0, 24, 0, 24)
        }

        sendButton = Button(this).apply {
            text = "Generate"
            isEnabled = false
        }

        outputTextView = TextView(this).apply {
            text = ""
            textSize = 14f
            setPadding(0, 24, 0, 0)
        }

        layout.addView(statusTextView)
        layout.addView(selectButton)
        layout.addView(promptEditText)
        layout.addView(sendButton)
        layout.addView(outputTextView)

        return layout
    }

    private fun setupListeners() {
        selectButton.setOnClickListener {
            // Launch file picker restricted to binary/octet-stream or general files
            filePickerLauncher.launch(arrayOf("*/*"))
        }

        sendButton.setOnClickListener {
            val prompt = promptEditText.text.toString()
            if (prompt.isNotBlank() && llamaModel != null) {
                runInference(prompt)
            }
        }
    }

    private fun importModelFile(uri: Uri) {
        statusTextView.text = "Importing model file..."
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
                }
            }
        }
    }

    private suspend fun loadModelIntoEngine(path: String) {
        try {
            // Initialize native GGUF model wrapper
            llamaModel = LlamaModel.load(path) {
                contextSize = 2048
                threads = 4
                temperature = 0.7f
            }
            withContext(Dispatchers.Main) {
                statusTextView.text = "Model Loaded Successfully!"
                sendButton.isEnabled = true
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                statusTextView.text = "Engine load error: ${e.localizedMessage}"
            }
        }
    }

    private fun runInference(prompt: String) {
        outputTextView.text = ""
        sendButton.isEnabled = false
        statusTextView.text = "Generating response..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Stream tokens back asynchronously
                llamaModel?.generateStream(prompt)?.collect { token ->
                    withContext(Dispatchers.Main) {
                        outputTextView.append(token)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    outputTextView.append("\nError during generation: ${e.localizedMessage}")
                }
            } finally {
                withContext(Dispatchers.Main) {
                    statusTextView.text = "Model Loaded & Ready"
                    sendButton.isEnabled = true
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up native resources when activity is destroyed
        try {
            llamaModel?.close()
        } catch (_: Exception) {}
    }
}
