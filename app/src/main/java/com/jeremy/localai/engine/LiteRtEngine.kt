package com.jeremy.localai.engine

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

class LiteRtEngine(private val context: Context) : AiEngine {

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    companion object {
        private const val TAG = "LiteRtEngine"
    }

    override suspend fun loadModel(path: String, options: EngineOptions) {
        val modelFile = File(path)
        if (!modelFile.exists()) {
            throw IllegalArgumentException("Model file not found at path: $path")
        }

        val cacheDirectory = context.cacheDir.absolutePath

        // 1. Attempt GPU Acceleration configuration
        var activeConfig = EngineConfig(
            modelPath = path,
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
                    modelPath = path,
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

        // Create active chat session/conversation
        conversation = engine?.createConversation()
    }

    override fun generateStream(prompt: String): Flow<String> = flow {
        val activeConversation = conversation ?: throw IllegalStateException("Engine or Conversation is not initialized. Call loadModel() first.")
        
        // Stream text chunks asynchronously and emit individual token strings
        activeConversation.sendMessageAsync(Message(prompt)).collect { messageChunk ->
            emit(messageChunk.text ?: "")
        }
    }.flowOn(Dispatchers.IO)

    override fun close() {
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
