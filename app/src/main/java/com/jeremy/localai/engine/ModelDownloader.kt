package com.jeremy.localai.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.text.DecimalFormat
import java.util.concurrent.TimeUnit

sealed class DownloadState {
    data class Progress(val progressBytes: Long, val totalBytes: Long, val percent: Float) : DownloadState()
    data class Success(val file: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

object ModelDownloader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun downloadModel(context: Context, urlString: String, fileName: String): Flow<DownloadState> = flow {
        val destinationFile = File(context.filesDir, fileName)
        var downloadedBytes = if (destinationFile.exists()) destinationFile.length() else 0L

        emit(DownloadState.Progress(downloadedBytes, 0, 0f))

        try {
            val requestBuilder = Request.Builder()
                .url(urlString)
                .header("User-Agent", "LocalAI-AndroidApp")

            if (downloadedBytes > 0) {
                requestBuilder.header("Range", "bytes=$downloadedBytes-")
            }

            val response = client.newCall(requestBuilder.build()).execute()

            if (!response.isSuccessful && response.code != 416) {
                emit(DownloadState.Error("Server error code: ${response.code}"))
                return@flow
            }

            val body = response.body
            if (body == null && response.code != 416) {
                emit(DownloadState.Error("Received empty response body from server"))
                return@flow
            }

            val contentLength = body?.contentLength() ?: 0L
            val totalBytes = if (response.code == 206) {
                contentLength + downloadedBytes
            } else {
                downloadedBytes = 0L
                destinationFile.delete()
                contentLength
            }

            if (response.code == 416 || (totalBytes > 0 && downloadedBytes >= totalBytes)) {
                emit(DownloadState.Success(destinationFile))
                return@flow
            }

            RandomAccessFile(destinationFile, "rw").use { raf ->
                if (response.code == 206) {
                    raf.seek(downloadedBytes)
                } else {
                    raf.setLength(0)
                }

                body?.byteStream()?.use { inputStream ->
                    val buffer = ByteArray(16 * 1024)
                    var bytesRead: Int
                    
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        raf.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        
                        val percent = if (totalBytes > 0) {
                            (downloadedBytes.toFloat() / totalBytes.toFloat()) * 100f
                        } else 0f
                        
                        emit(DownloadState.Progress(downloadedBytes, totalBytes, percent))
                    }
                }
            }

            emit(DownloadState.Success(destinationFile))
        } catch (e: Exception) {
            emit(DownloadState.Error(e.localizedMessage ?: "Connection interrupted or timed out"))
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
