package com.rawcapture.converter.recording

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException

/**
 * Video Processing Engine using FFmpegKit
 * 
 * FFmpegKit is the most reliable video transcoding library for Android.
 * Used by VLC, Google Photos, and many production apps.
 * 
 * Handles ALL codec negotiation, pixel format conversion, and encoding internally.
 * Works on ALL Android devices (API 24+).
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
    private var session: FFmpegSession? = null
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
            session?.cancel()
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
        Log.i(TAG, "STARTING FFMPEG VIDEO TRANSCODING")
        Log.i(TAG, "Target: ${targetWidth}x${targetHeight} @ ${targetFps}fps")
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

            // Copy source to temp file (FFmpeg needs file path, not content URI)
            updateState(status = "Copying source file...")
            Log.i(TAG, "Copying source to temp file")
            
            tempInputFile = File(context.cacheDir, "rawcapture_input_${System.currentTimeMillis()}.mp4")
            copySourceToTemp(sourceUri, tempInputFile)
            Log.i(TAG, "Source copied to: ${tempInputFile.absolutePath}")

            // Build FFmpeg command
            val command = buildCommand(
                tempInputFile.absolutePath,
                outputPath,
                targetWidth,
                targetHeight,
                targetFps,
                targetBitrate,
                audioEnabled
            )
            
            Log.i(TAG, "FFmpeg command: $command")
            updateState(status = "Starting transcoding...")

            // Execute FFmpeg
            var completed = false
            var success = false
            var errorMessage: String? = null

            session = FFmpegKit.executeAsync(command,
                { statistics ->
                    // Update progress from statistics
                    val duration = statistics.duration
                    val time = statistics.time
                    
                    if (duration > 0 && time > 0) {
                        val progress = (time.toFloat() / duration).coerceIn(0f, 0.99f)
                        val elapsed = System.currentTimeMillis() - startTimeMs
                        val remaining = if (progress > 0.01f) {
                            ((elapsed / progress) - elapsed).toLong()
                        } else 0L
                        
                        updateState(
                            progress = progress,
                            elapsedMs = elapsed,
                            remainingMs = remaining,
                            currentFps = statistics.videoFrameRate.toFloat()
                        )
                    }
                },
                { session ->
                    val returnCode = session.returnCode
                    if (ReturnCode.isSuccess(returnCode)) {
                        success = true
                        Log.i(TAG, "FFmpeg completed successfully")
                    } else if (ReturnCode.isCancel(returnCode)) {
                        Log.i(TAG, "FFmpeg cancelled")
                        isCancelled = true
                    } else {
                        errorMessage = session.failStackTrace
                        Log.e(TAG, "FFmpeg failed: $errorMessage")
                    }
                    completed = true
                }
            )

            // Wait for completion
            while (!completed && !isCancelled) {
                delay(500)
            }

            // Handle result
            if (isCancelled) {
                Log.i(TAG, "Processing cancelled")
                tempInputFile?.delete()
                File(outputPath).delete()
                return@withContext Result.Cancelled
            }

            // Cleanup temp file
            tempInputFile?.delete()
            tempInputFile = null

            if (success) {
                val elapsed = System.currentTimeMillis() - startTimeMs
                val outputFile = File(outputPath)
                val fileSize = if (outputFile.exists()) outputFile.length() else 0L

                Log.i(TAG, "========================================")
                Log.i(TAG, "TRANSCODING COMPLETE")
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
                val errorMsg = errorMessage ?: "FFmpeg failed"
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
            session = null
            Log.i(TAG, "Cleanup done")
        }
    }

    private fun copySourceToTemp(uri: Uri, tempFile: File) {
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IOException("Cannot open source file")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy source", e)
            throw IOException("Failed to copy source file: ${e.message}")
        }
    }

    private fun buildCommand(
        inputPath: String,
        outputPath: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        audioEnabled: Boolean
    ): String {
        val videoCodec = "libx264"
        val audioCodec = if (audioEnabled) "aac" else "an"
        val videoBitrate = "${bitrate / 1000}k"
        val audioBitrate = "128k"
        
        return "-i \"$inputPath\" " +
               "-c:v $videoCodec " +
               "-preset medium " +
               "-s ${width}x$height " +
               "-r $fps " +
               "-b:v $videoBitrate " +
               "-c:a $audioCodec " +
               "-b:a $audioBitrate " +
               "-movflags +faststart " +
               "-y \"$outputPath\""
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
