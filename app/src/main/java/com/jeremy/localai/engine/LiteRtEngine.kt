package com.jeremy.localai.engine

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Conversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

class LiteRtEngine(private val context: Context) {

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    companion object {
        private const val TAG = "LiteRtEngine"
    }

    /**
     * Initializes the LiteRT-LM engine using a hardware backend fallback strategy.
     * Attempts GPU acceleration first, falling back to CPU if necessary.
     */
    fun initialize(modelPath: String) {
        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            throw IllegalArgumentException("Model file not found at path: $modelPath")
        }

        val cacheDirectory = context.cacheDir.absolutePath

        // 1. Attempt GPU Acceleration configuration
        var activeConfig = EngineConfig(
            modelPath = modelPath,
            backend = Backend.GPU(),
            cacheDir = cacheDirectory
        )

        try {
            Log.i(TAG, "Attempting to initialize LiteRT engine with GPU backend...")
            engine = Engine(activeConfig)
            engine?.initialize()
            Log.i(TAG, "Successfully initialized LiteRT engine with GPU backend.")
        } catch (gpuException: Exception) {
            Log.w(TAG, "GPU backend initialization failed. Falling back to CPU backend.", gpuException)
            
            // 2. Fallback to CPU Backend if GPU fails
            try {
                activeConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(),
                    cacheDir = cacheDirectory
                )
                engine = Engine(activeConfig)
                engine?.initialize()
                Log.i(TAG, "Successfully initialized LiteRT engine with CPU fallback backend.")
            } catch (cpuException: Exception) {
                Log.e(TAG, "Critical failure: Both GPU and CPU engine initialization failed.", cpuException)
                throw cpuException
            }
        }

        // Create the active conversation session using correct API
        conversation = engine?.createConversation()
    }

    /**
     * Streams the generated model response token by token using Kotlin Flows and sendMessageAsync.
     */
    fun generateResponseStream(prompt: String): Flow<String> = flow {
        val activeConversation = conversation ?: throw IllegalStateException("Engine or Conversation is not initialized. Call initialize() first.")
        
        // Stream text response chunks asynchronously
        activeConversation.sendMessageAsync(prompt).collect { chunk ->
            emit(chunk)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Releases underlying native resources safely.
     */
    fun close() {
        try {
            conversation?.close()
            conversation = null
            engine?.close()
            engine = null
            Log.i(TAG, "LiteRtEngine resources released successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down LiteRtEngine", e)
        }
    }
}
