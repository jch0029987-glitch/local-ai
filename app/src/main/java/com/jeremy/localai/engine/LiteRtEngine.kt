package com.jeremy.localai.engine

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class LiteRtEngine(private val context: Context) : AiEngine {

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    override suspend fun loadModel(path: String, options: EngineOptions) {
        val config = EngineConfig(
            modelPath = path,
            backend = Backend.CPU,
            cacheDir = context.cacheDir.absolutePath
        )
        val loadedEngine = Engine(config)
        loadedEngine.initialize()
        engine = loadedEngine
        conversation = loadedEngine.createConversation()
    }

    override fun generateStream(prompt: String): Flow<String> = flow {
        val activeConv = conversation ?: throw IllegalStateException("LiteRT-LM Conversation not initialized")
        val response = activeConv.sendMessage(Message.of(prompt))
        emit(response.toString())
    }

    override fun close() {
        try {
            conversation?.close()
        } catch (_: Exception) {}
        try {
            engine?.close()
        } catch (_: Exception) {}
        conversation = null
        engine = null
    }
}
