package com.jeremy.localai.engine

import kotlinx.coroutines.flow.Flow

interface AiEngine : AutoCloseable {
    suspend fun loadModel(path: String, options: EngineOptions)
    fun generateStream(prompt: String): Flow<String>
}

data class EngineOptions(
    val threads: Int = 4,
    val contextSize: Int = 2048,
    val temperature: Float = 0.7f
)
