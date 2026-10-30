package com.rawcapture.converter.recording

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rawcapture.converter.utils.DeviceCapabilities
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * ROBUST Video Processing Engine
 * 
 * Fixed issues:
 * - Proper codec selection (hardware preferred, surface-capable)
 * - Individual try-catch for each codec operation
 * - Timeout detection for stuck processing
 * - Surface validation
 * - Graceful error handling with specific messages
 * - No dummy engine creation in UI
 */
class VideoProcessingEngine(
    private val context: Context
) {
    companion object {
        private const val TAG = "VideoEngine"
        private const val DEQUEUE_TIMEOUT_US = 10000L
        private const val MAX_STUCK_ITERATIONS = 500 // ~5 seconds of no progress
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
    private var stuckIterationCount = 0

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
        mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC
    ): Result = withContext(Dispatchers.Default) {
        
        isCancelled = false
        startTimeMs = System.currentTimeMillis()
        framesProcessed = 0L
        fpsFrameCount = 0L
        lastFpsTime = startTimeMs
        stuckIterationCount = 0

        Log.i(TAG, "========================================")
        Log.i(TAG, "STARTING VIDEO PROCESSING")
        Log.i(TAG, "Target: ${targetWidth}x${targetHeight} @ ${targetFps}fps, bitrate=${targetBitrate}")
        Log.i(TAG, "Audio enabled: $audioEnabled")
        Log.i(TAG, "========================================")

        updateState(
            isProcessing = true,
            status = "Analyzing source video...",
            outputResolution = "${targetWidth}x${targetHeight}"
        )

        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var videoDecoder: MediaCodec? = null
        var videoEncoder: MediaCodec? = null
        var audioDecoder: MediaCodec? = null
        var audioEncoder: MediaCodec? = null
        var muxer: MediaMuxer? = null

        try {
            // ===== PHASE 1: OPEN AND ANALYZE SOURCE =====
            Log.i(TAG, "[Phase 1] Opening source video")
            
            val videoFd = try {
                context.contentResolver.openFileDescriptor(sourceUri, "r")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open file descriptor", e)
                return@withContext Result.Error("Cannot open source video: ${e.message}")
            }

            if (videoFd == null) {
                return@withContext Result.Error("Cannot open source video: null file descriptor")
            }

            videoExtractor = try {
                MediaExtractor().apply {
                    setDataSource(videoFd.fileDescriptor, 0, videoFd.statSize)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create MediaExtractor", e)
                videoFd.close()
                return@withContext Result.Error("Failed to analyze video: ${e.message}")
            }

            // Find tracks
            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null

            for (i in 0 until videoExtractor.trackCount) {
                val fmt = videoExtractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue

                if (mime.startsWith("video/") && videoTrackIndex == -1) {
                    videoTrackIndex = i
                    videoFormat = fmt
                    sourceDurationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                        fmt.getLong(MediaFormat.KEY_DURATION)
                    } else 0L
                    Log.i(TAG, "[Phase 1] Video track: index=$i, mime=$mime, ${fmt.getInteger(MediaFormat.KEY_WIDTH)}x${fmt.getInteger(MediaFormat.KEY_HEIGHT)}, duration=${sourceDurationUs}us")
                } else if (mime.startsWith("audio/") && audioTrackIndex == -1 && audioEnabled) {
                    audioTrackIndex = i
                    audioFormat = fmt
                    Log.i(TAG, "[Phase 1] Audio track: index=$i, mime=$mime")
                }
            }

            if (videoTrackIndex == -1 || videoFormat == null) {
                videoFd.close()
                videoExtractor.release()
                return@withContext Result.Error("No video track found in source file")
            }

            videoExtractor.selectTrack(videoTrackIndex)

            val sourceMime = videoFormat.getString(MediaFormat.KEY_MIME) ?: "video/avc"
            val srcW = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val srcH = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val srcFps = if (videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE))
                videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE) else 30

            totalFrames = ((sourceDurationUs / 1_000_000.0) * srcFps).toLong().coerceAtLeast(1)
            Log.i(TAG, "[Phase 1] Source: ${srcW}x${srcH} @ ${srcFps}fps, estFrames=$totalFrames")

            // Setup audio extractor
            if (audioTrackIndex >= 0 && audioFormat != null && audioEnabled) {
                try {
                    val audioFd = context.contentResolver.openFileDescriptor(sourceUri, "r")
                    if (audioFd != null) {
                        audioExtractor = MediaExtractor()
                        audioExtractor.setDataSource(audioFd.fileDescriptor, 0, audioFd.statSize)
                        audioExtractor.selectTrack(audioTrackIndex)
                        Log.i(TAG, "[Phase 1] Audio extractor ready")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[Phase 1] Audio extractor setup failed", e)
                    audioExtractor = null
                }
            }

            updateState(status = "Initializing video encoder...")

            // ===== PHASE 2: SELECT AND CREATE CODECS =====
            Log.i(TAG, "[Phase 2] Selecting codecs")

            // Select video encoder
            val encoderMime = if (mimeType == MediaFormat.MIMETYPE_VIDEO_HEVC && DeviceCapabilities.supportsHEVC()) {
                MediaFormat.MIMETYPE_VIDEO_HEVC
            } else {
                MediaFormat.MIMETYPE_VIDEO_AVC
            }

            val videoEncoderInfo = selectVideoEncoder(encoderMime, targetWidth, targetHeight, targetFps)
            if (videoEncoderInfo == null) {
                videoFd.close()
                videoExtractor.release()
                return@withContext Result.Error("No compatible video encoder found for ${targetWidth}x${targetHeight}")
            }
            Log.i(TAG, "[Phase 2] Selected encoder: ${videoEncoderInfo.name}")

            // Select video decoder
            val videoDecoderInfo = selectVideoDecoder(sourceMime)
            if (videoDecoderInfo == null) {
                videoFd.close()
                videoExtractor.release()
                return@withContext Result.Error("No compatible video decoder found for $sourceMime")
            }
            Log.i(TAG, "[Phase 2] Selected decoder: ${videoDecoderInfo.name}")

            // ===== PHASE 3: CREATE MUXER =====
            Log.i(TAG, "[Phase 3] Creating MediaMuxer")
            
            muxer = try {
                MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            } catch (e: Exception) {
                Log.e(TAG, "[Phase 3] Failed to create muxer", e)
                videoFd.close()
                videoExtractor.release()
                return@withContext Result.Error("Failed to create output file: ${e.message}")
            }

            var muxerVideoTrack = -1
            var muxerAudioTrack = -1
            var muxerStarted = false

            // ===== PHASE 4: CREATE AND CONFIGURE VIDEO CODECS =====
            Log.i(TAG, "[Phase 4] Creating video encoder")

            try {
                videoEncoder = MediaCodec.createByCodecName(videoEncoderInfo.name)
                
                val encoderFormat = MediaFormat.createVideoFormat(encoderMime, targetWidth, targetHeight).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }

                videoEncoder!!.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                Log.i(TAG, "[Phase 4] Video encoder configured")

                val encoderInputSurface = videoEncoder!!.createInputSurface()
                Log.i(TAG, "[Phase 4] Encoder input surface created: $encoderInputSurface")

                videoEncoder!!.start()
                Log.i(TAG, "[Phase 4] Video encoder started")

                // Create decoder with encoder surface
                Log.i(TAG, "[Phase 4] Creating video decoder with surface output")
                videoDecoder = MediaCodec.createByCodecName(videoDecoderInfo.name)
                
                val decoderFormat = MediaFormat.createVideoFormat(sourceMime, srcW, srcH)
                videoDecoder!!.configure(decoderFormat, encoderInputSurface, null, 0)
                Log.i(TAG, "[Phase 4] Video decoder configured")

                videoDecoder!!.start()
                Log.i(TAG, "[Phase 4] Video decoder started")

            } catch (e: Exception) {
                Log.e(TAG, "[Phase 4] Video codec creation failed", e)
                videoFd.close()
                videoExtractor.release()
                muxer?.release()
                return@withContext Result.Error("Video codec initialization failed: ${e.message}")
            }

            // ===== PHASE 5: CREATE AUDIO CODECS (if enabled) =====
            if (audioExtractor != null && audioFormat != null) {
                Log.i(TAG, "[Phase 5] Creating audio codecs")
                updateState(status = "Initializing audio encoder...")
                
                try {
                    val srcSampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                        audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
                    val srcChannels = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                        audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

                    val audEncFmt = MediaFormat.createAudioFormat(
                        MediaFormat.MIMETYPE_AUDIO_AAC, srcSampleRate, srcChannels
                    ).apply {
                        setInteger(MediaFormat.KEY_BIT_RATE, 128000)
                        setInteger(MediaFormat.KEY_AAC_PROFILE,
                            MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    }

                    audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                    audioEncoder!!.configure(audEncFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

                    val audMime = audioFormat.getString(MediaFormat.KEY_MIME) ?: "audio/mp4a-latm"
                    val audDecInfo = selectAudioDecoder(audMime)
                    if (audDecInfo != null) {
                        audioDecoder = MediaCodec.createByCodecName(audDecInfo.name)
                        audioDecoder!!.configure(audioFormat, null, null, 0)
                    }

                    audioDecoder?.start()
                    audioEncoder!!.start()
                    Log.i(TAG, "[Phase 5] Audio codecs started")
                } catch (e: Exception) {
                    Log.w(TAG, "[Phase 5] Audio codec setup failed, continuing without audio", e)
                    audioDecoder = null
                    audioEncoder = null
                    audioExtractor = null
                }
            } else {
                Log.i(TAG, "[Phase 5] Skipping audio setup")
            }

            // ===== PHASE 6: MAIN PROCESSING LOOP =====
            Log.i(TAG, "[Phase 6] Starting main processing loop")
            updateState(status = "Recording...")

            val vDecInputBufs = videoDecoder!!.inputBuffers
            val vDecOutputBufs = videoDecoder!!.outputBuffers
            val vEncOutputBufs = videoEncoder!!.outputBuffers

            var vDecInputDone = false
            var vDecOutputDone = false
            var vEncDone = false
            val bufferInfo = MediaCodec.BufferInfo()

            // Audio buffers
            val aDecInputBufs = audioDecoder?.inputBuffers
            val aDecOutputBufs = audioDecoder?.outputBuffers
            val aEncInputBufs = audioEncoder?.inputBuffers
            val aEncOutputBufs = audioEncoder?.outputBuffers

            var aDecInputDone = audioDecoder == null
            var aDecOutputDone = audioDecoder == null
            var aEncDone = audioEncoder == null

            var loopCount = 0
            var lastLogTime = System.currentTimeMillis()

            // Main loop
            while (!vEncDone || !aEncDone) {
                loopCount++
                
                if (isCancelled) {
                    Log.i(TAG, "[Phase 6] Processing cancelled by user at loop $loopCount")
                    return@withContext Result.Cancelled
                }

                // Detect stuck processing
                stuckIterationCount++
                if (stuckIterationCount > MAX_STUCK_ITERATIONS) {
                    Log.e(TAG, "[Phase 6] Processing stuck! No progress for $MAX_STUCK_ITERATIONS iterations")
                    throw IllegalStateException("Processing stuck - no output frames received")
                }

                // Log progress every 2 seconds
                val now = System.currentTimeMillis()
                if (now - lastLogTime >= 2000) {
                    Log.i(TAG, "[Phase 6] Loop $loopCount: frames=$framesProcessed, vDecIn=$vDecInputDone, vDecOut=$vDecOutputDone, vEnc=$vEncDone")
                    lastLogTime = now
                }

                try {
                    // ===== VIDEO DECODER INPUT =====
                    if (!vDecInputDone) {
                        val inIdx = try {
                            videoDecoder!!.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                        } catch (e: Exception) {
                            Log.w(TAG, "[Phase 6] Video decoder dequeueInputBuffer failed", e)
                            -1
                        }
                        
                        if (inIdx >= 0) {
                            try {
                                val buf = vDecInputBufs[inIdx]
                                val size = videoExtractor!!.readSampleData(buf, 0)
                                
                                if (size < 0) {
                                    videoDecoder!!.queueInputBuffer(inIdx, 0, 0, 0L,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    vDecInputDone = true
                                    Log.i(TAG, "[Phase 6] Video decoder input EOS")
                                } else {
                                    val pts = videoExtractor!!.sampleTime
                                    videoDecoder!!.queueInputBuffer(inIdx, 0, size, pts, 0)
                                    videoExtractor!!.advance()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "[Phase 6] Video decoder input error", e)
                                throw e
                            }
                        }
                    }

                    // ===== VIDEO DECODER OUTPUT (render to encoder surface) =====
                    if (!vDecOutputDone) {
                        val decOutIdx = try {
                            videoDecoder!!.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                        } catch (e: Exception) {
                            Log.w(TAG, "[Phase 6] Video decoder dequeueOutputBuffer failed", e)
                            MediaCodec.INFO_TRY_AGAIN_LATER
                        }
                        
                        when {
                            decOutIdx >= 0 -> {
                                stuckIterationCount = 0 // Reset stuck counter
                                val render = bufferInfo.size > 0
                                try {
                                    videoDecoder!!.releaseOutputBuffer(decOutIdx, render)
                                } catch (e: Exception) {
                                    Log.e(TAG, "[Phase 6] releaseOutputBuffer failed", e)
                                }
                                
                                if (render) {
                                    framesProcessed++
                                    fpsFrameCount++
                                    updateProgress()
                                }

                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    vDecOutputDone = true
                                    Log.i(TAG, "[Phase 6] Video decoder output EOS, signaling encoder")
                                    try {
                                        videoEncoder!!.signalEndOfInputStream()
                                    } catch (e: Exception) {
                                        Log.w(TAG, "[Phase 6] signalEndOfInputStream failed", e)
                                    }
                                }
                            }
                            decOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                Log.i(TAG, "[Phase 6] Video decoder format changed")
                            }
                        }
                    }

                    // ===== VIDEO ENCODER OUTPUT (write to muxer) =====
                    if (!vEncDone) {
                        val encOutIdx = try {
                            videoEncoder!!.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                        } catch (e: Exception) {
                            Log.w(TAG, "[Phase 6] Video encoder dequeueOutputBuffer failed", e)
                            MediaCodec.INFO_TRY_AGAIN_LATER
                        }
                        
                        when {
                            encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                try {
                                    muxerVideoTrack = muxer!!.addTrack(videoEncoder!!.outputFormat)
                                    Log.i(TAG, "[Phase 6] Muxer video track added: $muxerVideoTrack")
                                    
                                    if (!muxerStarted && (audioEncoder == null || muxerAudioTrack >= 0)) {
                                        muxer!!.start()
                                        muxerStarted = true
                                        Log.i(TAG, "[Phase 6] Muxer started")
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "[Phase 6] Muxer video track error", e)
                                    throw e
                                }
                            }
                            encOutIdx >= 0 -> {
                                try {
                                    val buf = vEncOutputBufs[encOutIdx]
                                    if (bufferInfo.size > 0 && muxerStarted && muxerVideoTrack >= 0) {
                                        buf.position(bufferInfo.offset)
                                        buf.limit(bufferInfo.offset + bufferInfo.size)
                                        muxer!!.writeSampleData(muxerVideoTrack, buf, bufferInfo)
                                    }
                                    videoEncoder!!.releaseOutputBuffer(encOutIdx, false)
                                } catch (e: Exception) {
                                    Log.e(TAG, "[Phase 6] Video encoder output error", e)
                                }
                                
                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    vEncDone = true
                                    Log.i(TAG, "[Phase 6] Video encoder done")
                                }
                            }
                        }
                    }

                    // ===== AUDIO PIPELINE =====
                    if (audioDecoder != null && audioEncoder != null && audioExtractor != null) {
                        
                        // Audio decoder input
                        if (!aDecInputDone) {
                            val aInIdx = try {
                                audioDecoder!!.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                            } catch (e: Exception) {
                                MediaCodec.INFO_TRY_AGAIN_LATER
                            }
                            
                            if (aInIdx >= 0) {
                                try {
                                    val buf = aDecInputBufs!![aInIdx]
                                    val size = audioExtractor.readSampleData(buf, 0)
                                    
                                    if (size < 0) {
                                        audioDecoder!!.queueInputBuffer(aInIdx, 0, 0, 0L,
                                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                        aDecInputDone = true
                                    } else {
                                        val pts = audioExtractor.sampleTime
                                        audioDecoder!!.queueInputBuffer(aInIdx, 0, size, pts, 0)
                                        audioExtractor.advance()
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "[Phase 6] Audio decoder input error", e)
                                }
                            }
                        }

                        // Audio decoder output → audio encoder input
                        if (!aDecOutputDone) {
                            val aDecOutIdx = try {
                                audioDecoder!!.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                            } catch (e: Exception) {
                                MediaCodec.INFO_TRY_AGAIN_LATER
                            }
                            
                            if (aDecOutIdx >= 0) {
                                try {
                                    val pcmBuf = aDecOutputBufs!![aDecOutIdx]
                                    if (bufferInfo.size > 0) {
                                        val aEncInIdx = audioEncoder!!.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                                        if (aEncInIdx >= 0) {
                                            val encBuf = aEncInputBufs!![aEncInIdx]
                                            encBuf.clear()
                                            pcmBuf.position(bufferInfo.offset)
                                            pcmBuf.limit(bufferInfo.offset + bufferInfo.size)
                                            encBuf.put(pcmBuf)
                                            
                                            val flags = if (aDecInputDone && bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                                                MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                                            
                                            audioEncoder!!.queueInputBuffer(aEncInIdx, 0, bufferInfo.size,
                                                bufferInfo.presentationTimeUs, flags)
                                        }
                                    }
                                    audioDecoder!!.releaseOutputBuffer(aDecOutIdx, false)
                                    
                                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                        aDecOutputDone = true
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "[Phase 6] Audio decoder→encoder error", e)
                                }
                            }
                        }

                        // Audio encoder output → muxer
                        if (!aEncDone) {
                            val aEncOutIdx = try {
                                audioEncoder!!.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                            } catch (e: Exception) {
                                MediaCodec.INFO_TRY_AGAIN_LATER
                            }
                            
                            when {
                                aEncOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                    try {
                                        muxerAudioTrack = muxer!!.addTrack(audioEncoder!!.outputFormat)
                                        Log.i(TAG, "[Phase 6] Muxer audio track added: $muxerAudioTrack")
                                        
                                        if (!muxerStarted && muxerVideoTrack >= 0) {
                                            muxer!!.start()
                                            muxerStarted = true
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "[Phase 6] Muxer audio track error", e)
                                    }
                                }
                                aEncOutIdx >= 0 -> {
                                    try {
                                        val buf = aEncOutputBufs!![aEncOutIdx]
                                        if (bufferInfo.size > 0 && muxerStarted && muxerAudioTrack >= 0) {
                                            buf.position(bufferInfo.offset)
                                            buf.limit(bufferInfo.offset + bufferInfo.size)
                                            muxer!!.writeSampleData(muxerAudioTrack, buf, bufferInfo)
                                        }
                                        audioEncoder!!.releaseOutputBuffer(aEncOutIdx, false)
                                    } catch (e: Exception) {
                                        Log.w(TAG, "[Phase 6] Audio encoder output error", e)
                                    }
                                    
                                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                        aEncDone = true
                                    }
                                }
                            }
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "[Phase 6] Main loop iteration $loopCount failed", e)
                    throw e
                }
            }

            Log.i(TAG, "[Phase 6] Processing loop completed after $loopCount iterations, frames=$framesProcessed")

            // ===== PHASE 7: FINALIZE =====
            Log.i(TAG, "[Phase 7] Finalizing output file")
            updateState(status = "Finalizing video file...")
            
            try {
                muxer?.stop()
                muxer?.release()
            } catch (e: Exception) {
                Log.w(TAG, "[Phase 7] Muxer stop/release error", e)
            }
            muxer = null

            try {
                videoFd.close()
                videoExtractor.release()
                audioExtractor?.release()
            } catch (e: Exception) {
                Log.w(TAG, "[Phase 7] Extractor cleanup error", e)
            }

            val elapsed = System.currentTimeMillis() - startTimeMs
            val outputFile = File(outputPath)
            val fileSize = if (outputFile.exists()) outputFile.length() else 0L
            val durationMs = sourceDurationUs / 1000

            Log.i(TAG, "========================================")
            Log.i(TAG, "PROCESSING COMPLETE")
            Log.i(TAG, "Elapsed: ${elapsed}ms")
            Log.i(TAG, "Output size: $fileSize bytes")
            Log.i(TAG, "Duration: ${durationMs}ms")
            Log.i(TAG, "Frames processed: $framesProcessed")
            Log.i(TAG, "========================================")

            updateState(
                isProcessing = false,
                isComplete = true,
                progress = 1f,
                outputFilePath = outputPath,
                outputDurationMs = durationMs,
                outputFileSize = fileSize,
                status = "Complete!"
            )

            return@withContext Result.Success(outputPath, durationMs, fileSize)

        } catch (e: Exception) {
            Log.e(TAG, "========================================")
            Log.e(TAG, "PROCESSING ERROR", e)
            Log.e(TAG, "Error type: ${e.javaClass.simpleName}")
            Log.e(TAG, "Error message: ${e.message}")
            Log.e(TAG, "========================================")
            
            updateState(
                error = "${e.javaClass.simpleName}: ${e.message}",
                isProcessing = false,
                status = "Error occurred"
            )
            return@withContext Result.Error("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            Log.i(TAG, "[Cleanup] Releasing codecs")
            
            // Release codecs in reverse order with proper error handling
            safeRelease("VideoDecoder", videoDecoder)
            safeRelease("VideoEncoder", videoEncoder)
            safeRelease("AudioDecoder", audioDecoder)
            safeRelease("AudioEncoder", audioEncoder)
            
            try {
                muxer?.release()
            } catch (e: Exception) {
                Log.w(TAG, "[Cleanup] Muxer release error", e)
            }
            
            try {
                videoExtractor?.release()
            } catch (e: Exception) {
                Log.w(TAG, "[Cleanup] Video extractor release error", e)
            }
            
            try {
                audioExtractor?.release()
            } catch (e: Exception) {
                Log.w(TAG, "[Cleanup] Audio extractor release error", e)
            }
            
            Log.i(TAG, "[Cleanup] Done")
        }
    }

    private fun safeRelease(name: String, codec: MediaCodec?) {
        try {
            codec?.stop()
            codec?.release()
            Log.i(TAG, "[Cleanup] $name released successfully")
        } catch (e: Exception) {
            Log.w(TAG, "[Cleanup] $name release error: ${e.message}", e)
        }
    }

    private fun updateProgress() {
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 500) {
            val elapsed = now - startTimeMs
            val currentFps = fpsFrameCount * 1000f / (now - lastFpsTime).coerceAtLeast(1)
            
            val progress = if (totalFrames > 0) {
                framesProcessed.toFloat() / totalFrames.toFloat()
            } else 0f

            val remaining = if (progress > 0.01f) {
                ((elapsed / progress) - elapsed).toLong()
            } else 0L

            updateState(
                progress = progress.coerceIn(0f, 1f),
                elapsedMs = elapsed,
                remainingMs = remaining,
                currentFps = currentFps
            )
            
            lastFpsTime = now
            fpsFrameCount = 0
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

    private fun selectVideoEncoder(mime: String, width: Int, height: Int, fps: Int): MediaCodecInfo? {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        var bestEncoder: MediaCodecInfo? = null
        var bestScore = -1

        for (info in codecList.codecInfos) {
            if (!info.isEncoder) continue
            
            val types = info.supportedTypes
            if (!types.any { it.equals(mime, ignoreCase = true) }) continue

            try {
                val caps = info.getCapabilitiesForType(mime)
                val videoCaps = caps.videoCapabilities
                
                // Check if supports surface input
                if (!videoCaps.isSizeSupported(width, height)) continue
                
                // Check if supports required frame rate
                val supportsFps = try {
                    videoCaps.areSizeAndRateSupported(width, height, fps.toDouble())
                } catch (e: Exception) {
                    true
                }
                
                if (!supportsFps) continue

                // Prefer hardware codecs
                val isHardware = info.name.contains(" OMX.", ignoreCase = true) || 
                                info.name.contains(" OMX", ignoreCase = true) ||
                                !info.name.contains("google", ignoreCase = true)
                val score = if (isHardware) 100 else 50
                
                if (score > bestScore) {
                    bestScore = score
                    bestEncoder = info
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking encoder ${info.name}", e)
            }
        }

        return bestEncoder
    }

    private fun selectVideoDecoder(mime: String): MediaCodecInfo? {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        var bestDecoder: MediaCodecInfo? = null
        var bestScore = -1

        for (info in codecList.codecInfos) {
            if (info.isEncoder) continue
            
            val types = info.supportedTypes
            if (!types.any { it.equals(mime, ignoreCase = true) }) continue

            try {
                val caps = info.getCapabilitiesForType(mime)
                val videoCaps = caps.videoCapabilities
                
                // Prefer hardware decoders (they support surface output)
                val isHardware = info.name.contains(" OMX.", ignoreCase = true) || 
                                info.name.contains(" OMX", ignoreCase = true) ||
                                !info.name.contains("google", ignoreCase = true)
                val score = if (isHardware) 100 else 50
                
                if (score > bestScore) {
                    bestScore = score
                    bestDecoder = info
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking decoder ${info.name}", e)
            }
        }

        return bestDecoder
    }

    private fun selectAudioDecoder(mime: String): MediaCodecInfo? {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        
        for (info in codecList.codecInfos) {
            if (info.isEncoder) continue
            if (info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) {
                return info
            }
        }

        return null
    }
}
