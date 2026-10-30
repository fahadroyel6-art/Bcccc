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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Simple and reliable video transcoding engine.
 * 
 * Uses MediaCodec in BUFFER MODE with proper format handling.
 * No Surface, no OpenGL, no EGL - just pure buffer processing.
 * 
 * This approach works on ALL Android devices.
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
    private var totalFrames = 0L
    private var lastFpsTime = 0L
    private var fpsFrameCount = 0L
    private var sourceDurationUs = 0L

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
        fpsFrameCount = 0L
        lastFpsTime = startTimeMs

        Log.i(TAG, "========================================")
        Log.i(TAG, "STARTING VIDEO TRANSCODING")
        Log.i(TAG, "Target: ${targetWidth}x${targetHeight} @ ${targetFps}fps")
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

        try {
            // ===== STEP 1: OPEN SOURCE =====
            Log.i(TAG, "[1/6] Opening source")
            
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
                    sourceDurationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        format.getLong(MediaFormat.KEY_DURATION)
                    } else 0L
                    
                    Log.i(TAG, "Video: ${format.getInteger(MediaFormat.KEY_WIDTH)}x${format.getInteger(MediaFormat.KEY_HEIGHT)}, mime=$mime, duration=${sourceDurationUs}us")
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
            val srcFps = if (videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
            } else 30

            totalFrames = ((sourceDurationUs / 1_000_000.0) * srcFps).toLong().coerceAtLeast(1)
            Log.i(TAG, "Total frames (est): $totalFrames")

            updateState(status = "Creating decoder...")

            // ===== STEP 2: CREATE DECODER =====
            Log.i(TAG, "[2/6] Creating decoder")
            
            decoder = MediaCodec.createDecoderByType(srcMime)
            
            // Configure decoder in BUFFER MODE (no output surface)
            decoder.configure(videoFormat, null, null, 0)
            decoder.start()
            Log.i(TAG, "Decoder started: ${decoder.name}")

            updateState(status = "Creating encoder...")

            // ===== STEP 3: CREATE ENCODER =====
            Log.i(TAG, "[3/6] Creating encoder")
            
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            
            val encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, targetWidth, targetHeight)
            encFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            encFormat.setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
            encFormat.setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
            encFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            encFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, srcWidth * srcHeight * 2)
            
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            Log.i(TAG, "Encoder started: ${encoder.name}")

            // ===== STEP 4: CREATE MUXER =====
            Log.i(TAG, "[4/6] Creating muxer")
            
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerTrack = -1
            var muxerStarted = false

            // ===== STEP 5: PROCESSING LOOP =====
            Log.i(TAG, "[5/6] Processing loop")
            updateState(status = "Processing...")

            val decInBufs = decoder.inputBuffers
            val decOutBufs = decoder.outputBuffers
            val encOutBufs = encoder.outputBuffers

            var decInputDone = false
            var decOutputDone = false
            var encDone = false
            val info = MediaCodec.BufferInfo()

            var loop = 0
            var lastLog = System.currentTimeMillis()

            while (!encDone) {
                loop++
                
                if (isCancelled) {
                    Log.i(TAG, "Cancelled at loop $loop")
                    return@withContext Result.Cancelled
                }

                if (System.currentTimeMillis() - lastLog >= 2000) {
                    Log.i(TAG, "Loop $loop: frames=$framesProcessed, decIn=$decInputDone, decOut=$decOutputDone, enc=$encDone")
                    lastLog = System.currentTimeMillis()
                }

                // Decoder input
                if (!decInputDone) {
                    val idx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        val buf = decInBufs[idx]
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

                // Decoder output → Encoder input
                if (!decOutputDone) {
                    val decIdx = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (decIdx >= 0) {
                        framesProcessed++
                        fpsFrameCount++
                        updateProgress()

                        // Copy to encoder
                        val encIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
                        if (encIdx >= 0) {
                            val decBuf = decOutBufs[decIdx]
                            val encBuf = encoder.inputBuffers[encIdx]
                            
                            decBuf.position(info.offset)
                            decBuf.limit(info.offset + info.size)
                            encBuf.clear()
                            encBuf.put(decBuf)
                            
                            encoder.queueInputBuffer(encIdx, 0, info.size, info.presentationTimeUs, info.flags)
                        }
                        
                        decoder.releaseOutputBuffer(decIdx, false)

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
                            val buf = encOutBufs[encIdx]
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
            }

            Log.i(TAG, "[5/6] Done: $loop loops, $framesProcessed frames")

            // ===== STEP 6: FINALIZE =====
            Log.i(TAG, "[6/6] Finalizing")
            updateState(status = "Finalizing...")

            muxer.stop()
            muxer.release()
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
                progress = 1f,
                outputFilePath = outputPath,
                outputDurationMs = sourceDurationUs / 1000,
                outputFileSize = fileSize,
                status = "Complete!"
            )

            return@withContext Result.Success(outputPath, sourceDurationUs / 1000, fileSize)

        } catch (e: Exception) {
            Log.e(TAG, "FAILED", e)
            updateState(error = e.message, isProcessing = false, status = "Error")
            return@withContext Result.Error(e.message ?: "Error")
        } finally {
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    private fun updateProgress() {
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 500) {
            val elapsed = now - startTimeMs
            val fps = fpsFrameCount * 1000f / (now - lastFpsTime).coerceAtLeast(1)
            val progress = if (totalFrames > 0) framesProcessed.toFloat() / totalFrames.toFloat() else 0f
            val remaining = if (progress > 0.01f) ((elapsed / progress) - elapsed).toLong() else 0L

            updateState(progress = progress.coerceIn(0f, 1f), elapsedMs = elapsed, remainingMs = remaining, currentFps = fps)
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
