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
import java.nio.ByteBuffer

/**
 * Completely rewritten video processing engine with proper pipeline management.
 * 
 * KEY FIXES:
 * 1. Separate video and audio processing phases
 * 2. Better timeout handling
 * 3. Extensive logging for debugging
 * 4. Proper codec lifecycle management
 * 5. Non-blocking drain loops
 */
class VideoProcessingEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "VideoEngine"
        private const val DEQUEUE_TIMEOUT_US = 10000L
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
        mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC
    ): Result = withContext(Dispatchers.Default) {
        
        isCancelled = false
        startTimeMs = System.currentTimeMillis()
        framesProcessed = 0L
        fpsFrameCount = 0L
        lastFpsTime = startTimeMs

        Log.i(TAG, "=== STARTING VIDEO PROCESSING ===")
        Log.i(TAG, "Target: ${targetWidth}x${targetHeight} @ ${targetFps}fps, bitrate=${targetBitrate}")

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
            // ===== STEP 1: ANALYZE SOURCE VIDEO =====
            Log.i(TAG, "Step 1: Analyzing source video")
            
            val videoFd = context.contentResolver.openFileDescriptor(sourceUri, "r")
                ?: return@withContext Result.Error("Cannot open source video file descriptor")

            videoExtractor = MediaExtractor()
            videoExtractor.setDataSource(videoFd.fileDescriptor, 0, videoFd.statSize)

            // Find video and audio tracks
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
                    
                    Log.i(TAG, "Found video track: index=$i, mime=$mime, duration=${sourceDurationUs}us")
                } else if (mime.startsWith("audio/") && audioTrackIndex == -1 && audioEnabled) {
                    audioTrackIndex = i
                    audioFormat = fmt
                    Log.i(TAG, "Found audio track: index=$i, mime=$mime")
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
            Log.i(TAG, "Source video: ${srcW}x${srcH} @ ${srcFps}fps, estimated frames: $totalFrames")

            // Setup audio extractor
            if (audioTrackIndex >= 0 && audioFormat != null && audioEnabled) {
                val audioFd = context.contentResolver.openFileDescriptor(sourceUri, "r")
                if (audioFd != null) {
                    audioExtractor = MediaExtractor()
                    audioExtractor.setDataSource(audioFd.fileDescriptor, 0, audioFd.statSize)
                    audioExtractor.selectTrack(audioTrackIndex)
                    Log.i(TAG, "Audio extractor ready")
                }
            }

            // ===== STEP 2: SETUP VIDEO CODECS =====
            Log.i(TAG, "Step 2: Setting up video codecs")
            updateState(status = "Initializing video encoder...")

            val encoderMime = if (mimeType == MediaFormat.MIMETYPE_VIDEO_HEVC && DeviceCapabilities.supportsHEVC()) {
                MediaFormat.MIMETYPE_VIDEO_HEVC
            } else {
                MediaFormat.MIMETYPE_VIDEO_AVC
            }

            // Create muxer
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerVideoTrack = -1
            var muxerAudioTrack = -1
            var muxerStarted = false

            // Create video encoder
            val encoderFormat = MediaFormat.createVideoFormat(encoderMime, targetWidth, targetHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            videoEncoder = MediaCodec.createEncoderByType(encoderMime)
            videoEncoder!!.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderInputSurface = videoEncoder!!.createInputSurface()
            videoEncoder!!.start()
            Log.i(TAG, "Video encoder started, input surface created")

            // Create video decoder with encoder surface as output
            val decoderFormat = MediaFormat.createVideoFormat(sourceMime, srcW, srcH)
            val decoderName = findDecoderForMime(sourceMime)
            videoDecoder = MediaCodec.createByCodecName(decoderName)
            videoDecoder!!.configure(decoderFormat, encoderInputSurface, null, 0)
            videoDecoder!!.start()
            Log.i(TAG, "Video decoder started: $decoderName")

            // ===== STEP 3: SETUP AUDIO CODECS (if enabled) =====
            if (audioExtractor != null && audioFormat != null) {
                Log.i(TAG, "Step 3: Setting up audio codecs")
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
                    val audDecName = findDecoderForMime(audMime)
                    audioDecoder = MediaCodec.createByCodecName(audDecName)
                    audioDecoder!!.configure(audioFormat, null, null, 0)

                    audioDecoder!!.start()
                    audioEncoder!!.start()
                    Log.i(TAG, "Audio codecs started")
                } catch (e: Exception) {
                    Log.w(TAG, "Audio codec setup failed, continuing without audio", e)
                    audioDecoder = null
                    audioEncoder = null
                    audioExtractor = null
                }
            } else {
                Log.i(TAG, "Step 3: Skipping audio setup (not enabled)")
            }

            // ===== STEP 4: PROCESSING LOOP =====
            Log.i(TAG, "Step 4: Starting main processing loop")
            updateState(status = "Recording...")

            val vDecInputBufs = videoDecoder!!.inputBuffers
            val vDecOutputBufs = videoDecoder!!.outputBuffers
            val vEncOutputBufs = videoEncoder!!.outputBuffers

            var vDecInputDone = false
            var vDecOutputDone = false
            var vEncDone = false
            val bufferInfo = MediaCodec.BufferInfo()

            // Audio processing
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
                    Log.i(TAG, "Processing cancelled by user at loop $loopCount")
                    return@withContext Result.Cancelled
                }

                // Log progress every 2 seconds
                val now = System.currentTimeMillis()
                if (now - lastLogTime >= 2000) {
                    Log.i(TAG, "Loop $loopCount: frames=$framesProcessed, vDecIn=$vDecInputDone, vDecOut=$vDecOutputDone, vEnc=$vEncDone")
                    lastLogTime = now
                }

                // ===== VIDEO PIPELINE =====
                
                // Feed video decoder from extractor
                if (!vDecInputDone) {
                    val inIdx = videoDecoder!!.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = vDecInputBufs[inIdx]
                        val size = videoExtractor!!.readSampleData(buf, 0)
                        
                        if (size < 0) {
                            // End of stream
                            videoDecoder!!.queueInputBuffer(inIdx, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            vDecInputDone = true
                            Log.i(TAG, "Video decoder input EOS at loop $loopCount")
                        } else {
                            val pts = videoExtractor!!.sampleTime
                            videoDecoder!!.queueInputBuffer(inIdx, 0, size, pts, 0)
                            videoExtractor!!.advance()
                        }
                    }
                }

                // Drain video decoder → render to encoder surface
                if (!vDecOutputDone) {
                    val decOutIdx = videoDecoder!!.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                    
                    if (decOutIdx >= 0) {
                        val render = bufferInfo.size > 0
                        videoDecoder!!.releaseOutputBuffer(decOutIdx, render)
                        
                        if (render) {
                            framesProcessed++
                            fpsFrameCount++
                            updateProgress()
                        }

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            vDecOutputDone = true
                            Log.i(TAG, "Video decoder output EOS, signaling encoder")
                            videoEncoder!!.signalEndOfInputStream()
                        }
                    }
                }

                // Drain video encoder → muxer
                if (!vEncDone) {
                    val encOutIdx = videoEncoder!!.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                    
                    when {
                        encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            muxerVideoTrack = muxer!!.addTrack(videoEncoder!!.outputFormat)
                            Log.i(TAG, "Muxer video track added: $muxerVideoTrack")
                            
                            if (!muxerStarted && (audioEncoder == null || muxerAudioTrack >= 0)) {
                                muxer!!.start()
                                muxerStarted = true
                                Log.i(TAG, "Muxer started")
                            }
                        }
                        encOutIdx >= 0 -> {
                            val buf = vEncOutputBufs[encOutIdx]
                            if (bufferInfo.size > 0 && muxerStarted && muxerVideoTrack >= 0) {
                                buf.position(bufferInfo.offset)
                                buf.limit(bufferInfo.offset + bufferInfo.size)
                                muxer!!.writeSampleData(muxerVideoTrack, buf, bufferInfo)
                            }
                            videoEncoder!!.releaseOutputBuffer(encOutIdx, false)
                            
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                vEncDone = true
                                Log.i(TAG, "Video encoder done at loop $loopCount")
                            }
                        }
                    }
                }

                // ===== AUDIO PIPELINE =====
                if (audioDecoder != null && audioEncoder != null && audioExtractor != null) {
                    
                    // Feed audio decoder
                    if (!aDecInputDone) {
                        val aInIdx = audioDecoder!!.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                        if (aInIdx >= 0) {
                            val buf = aDecInputBufs!![aInIdx]
                            val size = audioExtractor.readSampleData(buf, 0)
                            
                            if (size < 0) {
                                audioDecoder!!.queueInputBuffer(aInIdx, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                aDecInputDone = true
                                Log.i(TAG, "Audio decoder input EOS")
                            } else {
                                val pts = audioExtractor.sampleTime
                                audioDecoder!!.queueInputBuffer(aInIdx, 0, size, pts, 0)
                                audioExtractor.advance()
                            }
                        }
                    }

                    // Drain audio decoder → audio encoder
                    if (!aDecOutputDone) {
                        val aDecOutIdx = audioDecoder!!.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                        
                        if (aDecOutIdx >= 0) {
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
                                Log.i(TAG, "Audio decoder output EOS")
                            }
                        }
                    }

                    // Drain audio encoder → muxer
                    if (!aEncDone) {
                        val aEncOutIdx = audioEncoder!!.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                        
                        when {
                            aEncOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                muxerAudioTrack = muxer!!.addTrack(audioEncoder!!.outputFormat)
                                Log.i(TAG, "Muxer audio track added: $muxerAudioTrack")
                                
                                if (!muxerStarted && muxerVideoTrack >= 0) {
                                    muxer!!.start()
                                    muxerStarted = true
                                    Log.i(TAG, "Muxer started (audio ready)")
                                }
                            }
                            aEncOutIdx >= 0 -> {
                                val buf = aEncOutputBufs!![aEncOutIdx]
                                if (bufferInfo.size > 0 && muxerStarted && muxerAudioTrack >= 0) {
                                    buf.position(bufferInfo.offset)
                                    buf.limit(bufferInfo.offset + bufferInfo.size)
                                    muxer!!.writeSampleData(muxerAudioTrack, buf, bufferInfo)
                                }
                                audioEncoder!!.releaseOutputBuffer(aEncOutIdx, false)
                                
                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    aEncDone = true
                                    Log.i(TAG, "Audio encoder done")
                                }
                            }
                        }
                    }
                }
            }

            Log.i(TAG, "Processing loop completed after $loopCount iterations")

            // ===== STEP 5: FINALIZE =====
            Log.i(TAG, "Step 5: Finalizing output file")
            updateState(status = "Finalizing video file...")
            
            muxer?.stop()
            muxer?.release()
            muxer = null

            videoFd.close()
            videoExtractor.release()
            audioExtractor?.release()

            val elapsed = System.currentTimeMillis() - startTimeMs
            val outputFile = File(outputPath)
            val fileSize = if (outputFile.exists()) outputFile.length() else 0L
            val durationMs = sourceDurationUs / 1000

            Log.i(TAG, "=== PROCESSING COMPLETE ===")
            Log.i(TAG, "Elapsed: ${elapsed}ms, Output size: $fileSize bytes, Duration: ${durationMs}ms")

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
            Log.e(TAG, "=== PROCESSING ERROR ===", e)
            updateState(error = e.message, isProcessing = false, status = "Error occurred")
            return@withContext Result.Error(e.message ?: "Unknown processing error")
        } finally {
            Log.i(TAG, "Cleaning up codecs")
            try { videoDecoder?.stop() } catch (e: Exception) { Log.w(TAG, "Error stopping video decoder", e) }
            try { videoDecoder?.release() } catch (e: Exception) { Log.w(TAG, "Error releasing video decoder", e) }
            try { videoEncoder?.stop() } catch (e: Exception) { Log.w(TAG, "Error stopping video encoder", e) }
            try { videoEncoder?.release() } catch (e: Exception) { Log.w(TAG, "Error releasing video encoder", e) }
            try { audioDecoder?.stop() } catch (e: Exception) { Log.w(TAG, "Error stopping audio decoder", e) }
            try { audioDecoder?.release() } catch (e: Exception) { Log.w(TAG, "Error releasing audio decoder", e) }
            try { audioEncoder?.stop() } catch (e: Exception) { Log.w(TAG, "Error stopping audio encoder", e) }
            try { audioEncoder?.release() } catch (e: Exception) { Log.w(TAG, "Error releasing audio encoder", e) }
            try { muxer?.release() } catch (e: Exception) { Log.w(TAG, "Error releasing muxer", e) }
            try { videoExtractor?.release() } catch (e: Exception) { Log.w(TAG, "Error releasing video extractor", e) }
            try { audioExtractor?.release() } catch (e: Exception) { Log.w(TAG, "Error releasing audio extractor", e) }
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

    private fun findDecoderForMime(mimeType: String): String {
        val codecInfos = MediaCodecList(MediaCodecList.ALL_CODECS)
        for (info in codecInfos.codecInfos) {
            if (info.isEncoder) continue
            for (type in info.supportedTypes) {
                if (type.equals(mimeType, ignoreCase = true)) {
                    return info.name
                }
            }
        }
        throw IllegalStateException("No decoder found for MIME type: $mimeType")
    }
}
