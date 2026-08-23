package com.jeremy.localai.engine

import android.content.Context
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class LiteRtEngine(private val context: Context) : AiEngine {
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    override suspend fun loadModel(path: String, options: EngineOptions) {
        try {
            conversation?.close()
            engine?.close()
        } catch (_: Exception) {}

        // Configure LiteRT-LM Engine parameters
        val config = EngineConfig(
            modelPath = path,
            maxNumTokens = options.contextSize
        )

        val createdEngine = Engine(config)
        createdEngine.initialize()
        
        engine = createdEngine
        conversation = createdEngine.createConversation()
    }

    override fun generateStream(prompt: String): Flow<String> = callbackFlow {
        val activeConv = conversation ?: throw IllegalStateException("LiteRT-LM conversation not initialized")
        
        try {
            // Send message asynchronously and stream token chunks back
            activeConv.sendMessageAsync(prompt).collect { tokenChunk ->
                trySend(tokenChunk)
            }
        } catch (e: Exception) {
            trySend("\nError: ${e.localizedMessage}")
        } finally {
            close()
        }
    }

    override fun close() {
        try {
            conversation?.close()
            engine?.close()
        } catch (_: Exception) {}
    }
}
