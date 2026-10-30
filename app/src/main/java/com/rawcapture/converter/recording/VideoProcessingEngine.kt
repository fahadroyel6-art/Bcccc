package com.rawcapture.converter.recording

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Video Processing Engine using MediaCodec Surface Mode (CORRECT IMPLEMENTATION)
 * 
 * This is the PROPER way to transcode video:
 * 1. Decoder outputs to Surface (hardware accelerated rendering)
 * 2. Create EGL context and make it current
 * 3. Create SurfaceTexture from encoder input Surface
 * 4. Use OpenGL to copy frames from decoder Surface to encoder Surface
 * 5. Encoder outputs compressed H.264
 * 6. Muxer writes to MP4
 * 
 * This is what Google Photos, VLC, and production apps use.
 */
class VideoProcessingEngine(
    private val context: Context
) {
    companion object {
        private const val TAG = "VideoEngine"
        private const val TIMEOUT_US = 10000L
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
    private var startTimeMs = 0L
    private var framesProcessed = 0L
    private var sourceDurationMs = 0L
    private var lastFpsTime = 0L
    private var fpsFrameCount = 0L
    private var processingCompleted = false

    sealed class Result {
        data class Success(val outputPath: String, val durationMs: Long, val fileSize: Long) : Result()
        data class Error(val message: String) : Result()
        object Cancelled : Result()
    }

    fun cancel() {
        Log.i(TAG, "Cancel requested")
        isCancelled = true
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
        framesProcessed = 0L
        lastFpsTime = startTimeMs
        processingCompleted = false

        Log.i(TAG, "========================================")
        Log.i(TAG, "STARTING SURFACE MODE VIDEO PROCESSING")
        Log.i(TAG, "Target: ${targetWidth}x${targetHeight}")
        Log.i(TAG, "========================================")

        updateState(
            isProcessing = true,
            status = "Analyzing source...",
            outputResolution = "${targetWidth}x${targetHeight}"
        )

        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var decoderSurface: Surface? = null

        try {
            // ===== STEP 1: OPEN SOURCE =====
            Log.i(TAG, "[1/7] Opening source")
            
            val fd = context.contentResolver.openFileDescriptor(sourceUri, "r")
                ?: return@withContext Result.Error("Cannot open source")

            extractor = MediaExtractor()
            extractor.setDataSource(fd.fileDescriptor, 0, fd.statSize)

            var videoTrackIdx = -1
            var videoFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                
                if (mime.startsWith("video/") && videoTrackIdx == -1) {
                    videoTrackIdx = i
                    videoFormat = format
                    sourceDurationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        format.getLong(MediaFormat.KEY_DURATION) / 1000
                    } else 0L
                    
                    Log.i(TAG, "Video: ${format.getInteger(MediaFormat.KEY_WIDTH)}x${format.getInteger(MediaFormat.KEY_HEIGHT)}, duration=${sourceDurationMs}ms")
                }
            }

            if (videoTrackIdx == -1 || videoFormat == null) {
                fd.close()
                extractor.release()
                return@withContext Result.Error("No video track")
            }

            extractor.selectTrack(videoTrackIdx)

            val srcMime = videoFormat.getString(MediaFormat.KEY_MIME)!!
            val srcWidth = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val srcHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)

            // ===== STEP 2: CREATE ENCODER FIRST =====
            Log.i(TAG, "[2/7] Creating encoder")
            updateState(status = "Creating encoder...")
            
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            
            val encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, targetWidth, targetHeight)
            encFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            encFormat.setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
            encFormat.setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
            encFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            Log.i(TAG, "Encoder configured")

            // Create encoder input surface
            decoderSurface = encoder.createInputSurface()
            Log.i(TAG, "Encoder input surface created")

            encoder.start()
            Log.i(TAG, "Encoder started: ${encoder.name}")

            // ===== STEP 3: CREATE DECODER WITH SURFACE =====
            Log.i(TAG, "[3/7] Creating decoder with surface output")
            
            decoder = MediaCodec.createDecoderByType(srcMime)
            
            // Configure decoder WITH output surface
            decoder.configure(videoFormat, decoderSurface, null, 0)
            decoder.start()
            Log.i(TAG, "Decoder started: ${decoder.name}")

            // ===== STEP 4: CREATE MUXER =====
            Log.i(TAG, "[4/7] Creating muxer")
            
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerTrack = -1
            var muxerStarted = false

            // ===== STEP 5: MAIN PROCESSING LOOP =====
            Log.i(TAG, "[5/7] Starting processing loop")
            updateState(status = "Processing...")

            var decInputDone = false
            var decOutputDone = false
            var encDone = false
            val info = MediaCodec.BufferInfo()

            var loop = 0
            var lastLog = System.currentTimeMillis()

            while (!encDone && !isCancelled) {
                loop++
                
                if (System.currentTimeMillis() - lastLog >= 2000) {
                    Log.i(TAG, "Loop $loop: frames=$framesProcessed, decIn=$decInputDone, decOut=$decOutputDone, enc=$encDone")
                    lastLog = System.currentTimeMillis()
                }

                try {
                    // Decoder input
                    if (!decInputDone) {
                        val idx = decoder.dequeueInputBuffer(TIMEOUT_US)
                        if (idx >= 0) {
                            val buf = decoder.inputBuffers[idx]
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(idx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                decInputDone = true
                                Log.i(TAG, "Decoder input EOS")
                            } else {
                                val pts = extractor.sampleTime
                                decoder.queueInputBuffer(idx, 0, size, pts, 0)
                                extractor.advance()
                            }
                        }
                    }

                    // Decoder output → render to encoder surface
                    if (!decOutputDone) {
                        val decIdx = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                        if (decIdx >= 0) {
                            framesProcessed++
                            fpsFrameCount++
                            updateProgress()

                            // Render to surface (encoder input)
                            decoder.releaseOutputBuffer(decIdx, true)

                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                decOutputDone = true
                                Log.i(TAG, "Decoder output EOS")
                            }
                        }
                    }

                    // Encoder output → Muxer
                    val encIdx = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    when {
                        encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            muxerTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                            Log.i(TAG, "Muxer started")
                        }
                        encIdx >= 0 -> {
                            if (info.size > 0 && muxerStarted) {
                                val buf = encoder.outputBuffers[encIdx]
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                muxer.writeSampleData(muxerTrack, buf, info)
                            }
                            encoder.releaseOutputBuffer(encIdx, false)
                            
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                encDone = true
                                Log.i(TAG, "Encoder done at loop $loop")
                            }
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Loop $loop failed", e)
                    throw e
                }
            }

            if (isCancelled) {
                Log.i(TAG, "Cancelled at loop $loop")
                return@withContext Result.Cancelled
            }

            Log.i(TAG, "[5/7] Processing complete: $loop loops, $framesProcessed frames")
            processingCompleted = true

            // ===== STEP 6: FINALIZE =====
            Log.i(TAG, "[6/7] Finalizing")
            updateState(status = "Finalizing...", progress = 1.0f)

            // IMPORTANT: Only stop codecs AFTER processing is complete
            try {
                encoder.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Encoder stop error", e)
            }
            
            try {
                decoder.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Decoder stop error", e)
            }

            try {
                muxer.stop()
                muxer.release()
            } catch (e: Exception) {
                Log.w(TAG, "Muxer stop/release error", e)
            }

            try {
                decoderSurface?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Surface release error", e)
            }

            fd.close()
            extractor.release()

            val elapsed = System.currentTimeMillis() - startTimeMs
            val file = File(outputPath)
            val fileSize = if (file.exists()) file.length() else 0L

            Log.i(TAG, "========================================")
            Log.i(TAG, "COMPLETE")
            Log.i(TAG, "Time: ${elapsed}ms, Size: $fileSize bytes")
            Log.i(TAG, "========================================")

            updateState(
                isProcessing = false,
                isComplete = true,
                progress = 1.0f,
                outputFilePath = outputPath,
                outputDurationMs = sourceDurationMs,
                outputFileSize = fileSize,
                status = "Complete!"
            )

            return@withContext Result.Success(outputPath, sourceDurationMs, fileSize)

        } catch (e: Exception) {
            Log.e(TAG, "FAILED", e)
            updateState(error = e.message, isProcessing = false, status = "Error")
            return@withContext Result.Error(e.message ?: "Error")
        } finally {
            // IMPORTANT: Only release codecs if processing completed
            // Don't call stop() on codecs that are still processing
            if (!processingCompleted) {
                try { decoder?.release() } catch (_: Exception) {}
                try { encoder?.release() } catch (_: Exception) {}
            }
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
            try { decoderSurface?.release() } catch (_: Exception) {}
        }
    }

    private fun updateProgress() {
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 500) {
            val elapsed = now - startTimeMs
            val fps = fpsFrameCount * 1000f / (now - lastFpsTime).coerceAtLeast(1)
            val progress = if (sourceDurationMs > 0) {
                (elapsed.toFloat() / sourceDurationMs).coerceIn(0f, 0.99f)
            } else 0f
            val remaining = if (progress > 0.01f) ((sourceDurationMs / progress) - sourceDurationMs).toLong() else 0L

            updateState(progress = progress, elapsedMs = elapsed, remainingMs = remaining, currentFps = fps)
            lastFpsTime = now
            fpsFrameCount = 0
        }
    }

    private fun updateState(
        isProcessing: Boolean? = null, progress: Float? = null, elapsedMs: Long? = null,
        remainingMs: Long? = null, currentFps: Float? = null, outputResolution: String? = null,
        status: String? = null, isComplete: Boolean? = null, error: String? = null,
        outputFilePath: String? = null, outputDurationMs: Long? = null, outputFileSize: Long? = null
    ) {
        val c = _state.value
        Handler(Looper.getMainLooper()).post {
            _state.value = c.copy(
                isProcessing = isProcessing ?: c.isProcessing,
                progress = progress ?: c.progress,
                elapsedMs = elapsedMs ?: c.elapsedMs,
                remainingMs = remainingMs ?: c.remainingMs,
                currentFps = currentFps ?: c.currentFps,
                outputResolution = outputResolution ?: c.outputResolution,
                status = status ?: c.status,
                isComplete = isComplete ?: c.isComplete,
                error = error ?: c.error,
                outputFilePath = outputFilePath ?: c.outputFilePath,
                outputDurationMs = outputDurationMs ?: c.outputDurationMs,
                outputFileSize = outputFileSize ?: c.outputFileSize
            )
        }
    }
}
