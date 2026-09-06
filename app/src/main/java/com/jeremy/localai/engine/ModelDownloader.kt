package com.jeremy.localai.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.text.DecimalFormat

sealed class DownloadState {
    data class Progress(val progressBytes: Long, val totalBytes: Long, val percent: Float) : DownloadState()
    data class Success(val file: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

object ModelDownloader {
    private val client = OkHttpClient()

    fun downloadModel(context: Context, urlString: String, fileName: String): Flow<DownloadState> = flow {
        emit(DownloadState.Progress(0, 0, 0f))
        val destinationFile = File(context.filesDir, fileName)

        try {
            val request = Request.Builder().url(urlString).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                emit(DownloadState.Error("Server returned code ${response.code}"))
                return@flow
            }

            val body = response.body
            if (body == null) {
                emit(DownloadState.Error("Empty response body"))
                return@flow
            }

            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            body.byteStream().use { inputStream ->
                FileOutputStream(destinationFile).use { outputStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        val percent = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes.toFloat()) * 100f else 0f
                        emit(DownloadState.Progress(downloadedBytes, totalBytes, percent))
                    }
                }
            }

            emit(DownloadState.Success(destinationFile))
        } catch (e: Exception) {
            emit(DownloadState.Error(e.localizedMessage ?: "Unknown download error"))
        }
    }.flowOn(Dispatchers.IO)

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val mb = bytes / (1024.0 * 1024.0)
        val gb = mb / 1024.0
        return if (gb >= 1.0) {
            DecimalFormat("#.##").format(gb) + " GB"
        } else {
            DecimalFormat("#.##").format(mb) + " MB"
        }
    }
}
