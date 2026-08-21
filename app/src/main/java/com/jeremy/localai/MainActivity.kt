package com.jeremy.localai

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
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

    private var llamaModel: LlamaModel? = null
    private var modelPath: String? = null

    // Storage Access Framework file picker for GGUF weights
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selectedUri ->
            importModelFile(selectedUri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 1. Enable modern Edge-to-Edge rendering built into AndroidX Activity
        enableEdgeToEdge()
        
        super.onCreate(savedInstanceState)
        
        // 2. Inflate from the XML layout file
        setContentView(R.layout.activity_main)

        // Bind UI elements from activity_main.xml
        statusTextView = findViewById(R.id.statusTextView)
        outputTextView = findViewById(R.id.outputTextView)
        promptEditText = findViewById(R.id.promptEditText)
        selectButton = findViewById(R.id.selectButton)
        sendButton = findViewById(R.id.sendButton)

        // 3. Handle system bar padding insets dynamically
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainLayout)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setupListeners()
    }

    private fun setupListeners() {
        selectButton.setOnClickListener {
            // Launch document picker restricted to valid model binaries/files
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
        try {
            llamaModel?.close()
        } catch (_: Exception) {}
    }
}
