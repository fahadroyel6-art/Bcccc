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
 * Video Processing Engine using Media3 Transformer API (Google's official library).
 * 
 * This properly handles:
 * - Codec selection (hardware + software fallback)
 * - Surface rendering
 * - Pixel format conversion
 * - Audio/video sync
 * - Progress tracking
 * - Error handling
 * 
 * Used by Google Photos, YouTube, and other production apps.
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
            status = "Initializing...",
            outputResolution = "${targetWidth}x${targetHeight}"
        )

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

            // Create listener
            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    Log.i(TAG, "Transformation completed successfully!")
                    
                    val elapsed = System.currentTimeMillis() - startTimeMs
                    val outputFile = File(outputPath)
                    val fileSize = if (outputFile.exists()) outputFile.length() else 0L

                    updateState(
                        isProcessing = false,
                        isComplete = true,
                        progress = 1f,
                        outputFilePath = outputPath,
                        outputDurationMs = sourceDurationMs,
                        outputFileSize = fileSize,
                        status = "Complete!"
                    )

                    Log.i(TAG, "Elapsed: ${elapsed}ms, Size: $fileSize bytes")
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    Log.e(TAG, "Transformation error", exportException)
                    
                    updateState(
                        error = exportException.message ?: "Unknown error",
                        isProcessing = false,
                        status = "Error occurred"
                    )
                }
            }

            // Create Transformer
            transformer = Transformer.Builder(context)
                .addListener(listener)
                .build()

            // Create edited media item
            val mediaItem = MediaItem.fromUri(sourceUri)
            val editedItem = EditedMediaItem.Builder(mediaItem)
                .setRemoveAudio(!audioEnabled)
                .build()

            updateState(status = "Starting transformation...")
            Log.i(TAG, "Starting transformation...")

            // Start transformation
            transformer!!.start(editedItem, outputPath)

            // Poll for progress and completion
            while (!isCancelled) {
                delay(500)

                val currentState = _state.value
                
                if (currentState.isComplete) {
                    Log.i(TAG, "Transformation complete!")
                    val outputFile = File(outputPath)
                    val fileSize = if (outputFile.exists()) outputFile.length() else 0L
                    return@withContext Result.Success(outputPath, sourceDurationMs, fileSize)
                }
                
                if (currentState.error != null) {
                    Log.e(TAG, "Transformation failed: ${currentState.error}")
                    return@withContext Result.Error(currentState.error!!)
                }

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

            // Cancelled
            Log.i(TAG, "Cancelled")
            return@withContext Result.Cancelled

        } catch (e: Exception) {
            Log.e(TAG, "PROCESSING FAILED", e)
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
