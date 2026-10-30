package com.rawcapture.converter.recording

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Video Processing Engine using Media3 Transformer API
 * 
 * Fixed issues:
 * 1. Copy source to temp file (Transformer needs file path, not content URI)
 * 2. Proper error handling and logging
 * 3. Better completion detection
 */
class VideoProcessingEngine(
    private val context: Context
) {
    companion object {
        private const val TAG = "VideoEngine"
    }

    data class ProcessingState(
        val isProcessing: Boolean = false,
        val progress: Float = 0f,
        val elapsedMs: Long = 0L,
        val remainingMs: Long = 0L,
        val currentFps: Float = 0f,
        val outputResolution: String = "",
        val status: String = "Initializing...",
        val isComplete: Boolean = false,
        val error: String? = null,
        val outputFilePath: String? = null,
        val outputDurationMs: Long = 0L,
        val outputFileSize: Long = 0L
    )

    private val _state = MutableStateFlow(ProcessingState())
    val state: StateFlow<ProcessingState> = _state

    @Volatile private var isCancelled = false
    private var transformer: Transformer? = null
    private var startTimeMs = 0L
    private var sourceDurationMs = 0L

    sealed class Result {
        data class Success(val outputPath: String, val durationMs: Long, val fileSize: Long) : Result()
        data class Error(val message: String) : Result()
        object Cancelled : Result()
    }

    fun cancel() {
        Log.i(TAG, "Cancel requested")
        isCancelled = true
        try {
            transformer?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Cancel error", e)
        }
    }

    suspend fun process(
        sourceUri: Uri,
        outputPath: String,
        targetWidth: Int,
        targetHeight: Int,
        targetFps: Int,
        targetBitrate: Int,
        audioEnabled: Boolean,
        mimeType: String = "video/avc"
    ): Result = withContext(Dispatchers.Default) {
        
        isCancelled = false
        startTimeMs = System.currentTimeMillis()

        Log.i(TAG, "========================================")
        Log.i(TAG, "STARTING MEDIA3 TRANSFORMER")
        Log.i(TAG, "Source: $sourceUri")
        Log.i(TAG, "Output: $outputPath")
        Log.i(TAG, "========================================")

        updateState(
            isProcessing = true,
            status = "Preparing...",
            outputResolution = "${targetWidth}x${targetHeight}"
        )

        var tempInputFile: File? = null

        try {
            // Get source duration
            try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(context, sourceUri)
                val durationStr = retriever.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                )
                sourceDurationMs = durationStr?.toLongOrNull() ?: 0L
                retriever.release()
                Log.i(TAG, "Source duration: ${sourceDurationMs}ms")
            } catch (e: Exception) {
                Log.w(TAG, "Could not get duration", e)
            }

            // Copy source to temp file (Transformer needs file path)
            updateState(status = "Copying source...")
            Log.i(TAG, "Copying source to temp file")
            
            tempInputFile = File(context.cacheDir, "rawcapture_input_${System.currentTimeMillis()}.mp4")
            copySourceToTemp(sourceUri, tempInputFile)
            Log.i(TAG, "Source copied to: ${tempInputFile.absolutePath}")

            // Create listener
            var completed = false
            var success = false
            var errorMessage: String? = null

            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    Log.i(TAG, "Transformation completed successfully!")
                    success = true
                    completed = true
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    Log.e(TAG, "Transformation error", exportException)
                    errorMessage = exportException.message ?: "Unknown error"
                    completed = true
                }
            }

            // Create Transformer
            transformer = Transformer.Builder(context)
                .addListener(listener)
                .build()

            // Create edited media item from FILE (not URI)
            val mediaItem = MediaItem.fromUri(Uri.fromFile(tempInputFile))
            val editedItem = EditedMediaItem.Builder(mediaItem)
                .setRemoveAudio(!audioEnabled)
                .build()

            updateState(status = "Starting transformation...")
            Log.i(TAG, "Starting transformation...")

            // Start transformation
            transformer!!.start(editedItem, outputPath)

            // Poll for completion
            while (!completed && !isCancelled) {
                delay(500)

                // Update progress from elapsed time
                val elapsed = System.currentTimeMillis() - startTimeMs
                if (sourceDurationMs > 0) {
                    val progress = (elapsed.toFloat() / sourceDurationMs).coerceIn(0f, 0.95f)
                    val remaining = ((sourceDurationMs / progress) - sourceDurationMs).toLong().coerceAtLeast(0)
                    
                    updateState(
                        progress = progress,
                        elapsedMs = elapsed,
                        remainingMs = remaining
                    )
                }
            }

            // Handle cancellation
            if (isCancelled) {
                Log.i(TAG, "Cancelled")
                tempInputFile?.delete()
                File(outputPath).delete()
                return@withContext Result.Cancelled
            }

            // Cleanup temp file
            tempInputFile?.delete()
            tempInputFile = null

            // Handle result
            if (success) {
                val elapsed = System.currentTimeMillis() - startTimeMs
                val outputFile = File(outputPath)
                val fileSize = if (outputFile.exists()) outputFile.length() else 0L

                Log.i(TAG, "========================================")
                Log.i(TAG, "TRANSFORMATION COMPLETE")
                Log.i(TAG, "Time: ${elapsed}ms")
                Log.i(TAG, "Size: $fileSize bytes")
                Log.i(TAG, "========================================")

                updateState(
                    isProcessing = false,
                    isComplete = true,
                    progress = 1f,
                    outputFilePath = outputPath,
                    outputDurationMs = sourceDurationMs,
                    outputFileSize = fileSize,
                    status = "Complete!"
                )

                return@withContext Result.Success(outputPath, sourceDurationMs, fileSize)
            } else {
                val errorMsg = errorMessage ?: "Transformation failed"
                Log.e(TAG, "Processing failed: $errorMsg")
                updateState(
                    error = errorMsg,
                    isProcessing = false,
                    status = "Error occurred"
                )
                File(outputPath).delete()
                return@withContext Result.Error(errorMsg)
            }

        } catch (e: Exception) {
            Log.e(TAG, "PROCESSING FAILED", e)
            tempInputFile?.delete()
            File(outputPath).delete()
            updateState(
                error = e.message,
                isProcessing = false,
                status = "Error"
            )
            return@withContext Result.Error(e.message ?: "Unknown error")
        } finally {
            transformer = null
            Log.i(TAG, "Cleanup done")
        }
    }

    private fun copySourceToTemp(uri: Uri, tempFile: File) {
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw java.io.IOException("Cannot open source file")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy source", e)
            throw java.io.IOException("Failed to copy source file: ${e.message}")
        }
    }

    private fun updateState(
        isProcessing: Boolean? = null,
        progress: Float? = null,
        elapsedMs: Long? = null,
        remainingMs: Long? = null,
        currentFps: Float? = null,
        outputResolution: String? = null,
        status: String? = null,
        isComplete: Boolean? = null,
        error: String? = null,
        outputFilePath: String? = null,
        outputDurationMs: Long? = null,
        outputFileSize: Long? = null
    ) {
        val current = _state.value
        Handler(Looper.getMainLooper()).post {
            _state.value = current.copy(
                isProcessing = isProcessing ?: current.isProcessing,
                progress = progress ?: current.progress,
                elapsedMs = elapsedMs ?: current.elapsedMs,
                remainingMs = remainingMs ?: current.remainingMs,
                currentFps = currentFps ?: current.currentFps,
                outputResolution = outputResolution ?: current.outputResolution,
                status = status ?: current.status,
                isComplete = isComplete ?: current.isComplete,
                error = error ?: current.error,
                outputFilePath = outputFilePath ?: current.outputFilePath,
                outputDurationMs = outputDurationMs ?: current.outputDurationMs,
                outputFileSize = outputFileSize ?: current.outputFileSize
            )
        }
    }
}
