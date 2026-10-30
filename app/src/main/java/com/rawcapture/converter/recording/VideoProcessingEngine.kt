package com.rawcapture.converter.recording

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
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
 * Core video processing engine.
 * Pipeline: Source → MediaExtractor → MediaCodec(Decoder) → Surface → MediaCodec(Encoder) → MediaMuxer → Output
 */
class VideoProcessingEngine(
    private val context: Context,
    private val scope: CoroutineScope
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

    sealed class Result {
        data class Success(val outputPath: String, val durationMs: Long, val fileSize: Long) : Result()
        data class Error(val message: String) : Result()
        object Cancelled : Result()
    }

    fun cancel() { isCancelled = true }

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
            // ---- Open source ----
            val videoFd = context.contentResolver.openFileDescriptor(sourceUri, "r")
                ?: return@withContext Result.Error("Cannot open source video")

            videoExtractor = MediaExtractor()
            videoExtractor.setFileDescriptor(videoFd.fileDescriptor)

            // ---- Find tracks ----
            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null
            var sourceDuration = 0L

            for (i in 0 until videoExtractor.trackCount) {
                val fmt = videoExtractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoTrackIndex == -1) {
                    videoTrackIndex = i
                    videoFormat = fmt
                    if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                        sourceDuration = fmt.getLong(MediaFormat.KEY_DURATION)
                    }
                } else if (mime.startsWith("audio/") && audioTrackIndex == -1 && audioEnabled) {
                    audioTrackIndex = i
                    audioFormat = fmt
                }
            }

            if (videoTrackIndex == -1 || videoFormat == null) {
                videoFd.close()
                return@withContext Result.Error("No video track found in source file")
            }

            videoExtractor.selectTrack(videoTrackIndex)

            val sourceMime = videoFormat.getString(MediaFormat.KEY_MIME) ?: "video/avc"
            val srcW = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val srcH = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val srcFps = if (videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE))
                videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE) else 30

            // Estimate total frames
            totalFrames = if (videoFormat.containsKey(MediaFormat.KEY_FRAME_COUNT)) {
                videoFormat.getInteger(MediaFormat.KEY_FRAME_COUNT).toLong()
            } else {
                ((sourceDuration / 1_000_000.0) * srcFps).toLong().coerceAtLeast(1)
            }

            Log.i(TAG, "Source: ${srcW}x${srcH} @ ${srcFps}fps, dur=${sourceDuration}us, tracks=v:$videoTrackIndex a:$audioTrackIndex")
            Log.i(TAG, "Target: ${targetWidth}x${targetHeight} @ ${targetFps}fps, bitrate=${targetBitrate}")

            // ---- Setup audio extractor (separate instance) ----
            if (audioTrackIndex >= 0 && audioFormat != null && audioEnabled) {
                val audioFd = context.contentResolver.openFileDescriptor(sourceUri, "r")
                if (audioFd != null) {
                    audioExtractor = MediaExtractor()
                    audioExtractor.setFileDescriptor(audioFd.fileDescriptor)
                    audioExtractor.selectTrack(audioTrackIndex)
                }
            }

            updateState(status = "Initializing encoder...")

            // ---- Setup Muxer ----
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerVideoTrack = -1
            var muxerAudioTrack = -1
            var muxerVideoFormatReady = false
            var muxerAudioFormatReady = audioEncoder == null // If no audio, we only wait for video
            var muxerStarted = false

            fun tryStartMuxer() {
                if (!muxerStarted && muxerVideoFormatReady && muxerAudioFormatReady) {
                    muxer!!.start()
                    muxerStarted = true
                    Log.i(TAG, "Muxer started, vTrack=$muxerVideoTrack aTrack=$muxerAudioTrack")
                }
            }

            // ---- Setup Video Encoder ----
            val encoderMime = if (mimeType == MediaFormat.MIMETYPE_VIDEO_HEVC && DeviceCapabilities.supportsHEVC()) {
                MediaFormat.MIMETYPE_VIDEO_HEVC
            } else {
                MediaFormat.MIMETYPE_VIDEO_AVC
            }

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

            // ---- Setup Video Decoder ----
            val decoderFormat = MediaFormat.createVideoFormat(sourceMime, srcW, srcH)
            val decoderName = findDecoderForMime(sourceMime)
            Log.i(TAG, "Using video decoder: $decoderName")
            videoDecoder = MediaCodec.createByCodecName(decoderName)
            videoDecoder!!.configure(decoderFormat, encoderInputSurface, null, 0)

            // ---- Setup Audio Encoder ----
            if (audioTrackIndex >= 0 && audioFormat != null && audioEnabled) {
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
                } catch (e: Exception) {
                    Log.w(TAG, "Audio encoder failed, disabling audio", e)
                    audioEncoder = null
                    muxerAudioFormatReady = true
                }
            } else {
                muxerAudioFormatReady = true
            }

            // ---- Setup Audio Decoder ----
            if (audioEncoder != null && audioFormat != null && audioExtractor != null) {
                try {
                    val audMime = audioFormat.getString(MediaFormat.KEY_MIME) ?: "audio/mp4a-latm"
                    val audDecName = findDecoderForMime(audMime)
                    audioDecoder = MediaCodec.createByCodecName(audDecName)
                    audioDecoder!!.configure(audioFormat, null, null, 0)
                } catch (e: Exception) {
                    Log.w(TAG, "Audio decoder failed", e)
                    audioDecoder = null
                }
            }

            // ---- Start all codecs ----
            videoDecoder!!.start()
            videoEncoder!!.start()
            audioDecoder?.start()
            audioEncoder?.start()

            updateState(status = "Recording...")

            // ---- Processing buffers ----
            val vDecInputBufs = videoDecoder!!.inputBuffers
            val vDecOutputBufs = videoDecoder!!.outputBuffers
            val vEncOutputBufs = videoEncoder!!.outputBuffers
            val aDecInputBufs = audioDecoder?.inputBuffers
            val aDecOutputBufs = audioDecoder?.outputBuffers
            val aEncInputBufs = audioEncoder?.inputBuffers
            val aEncOutputBufs = audioEncoder?.outputBuffers

            var vDecDone = false
            var vEncDone = false
            var aDecDone = audioDecoder == null
            var aEncDone = audioEncoder == null
            val bufferInfo = MediaCodec.BufferInfo()

            // ---- Main loop ----
            while (!vEncDone || !aEncDone) {
                if (isCancelled) return@withContext Result.Cancelled

                // --- Feed video decoder ---
                if (!vDecDone) {
                    val inIdx = videoDecoder!!.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = vDecInputBufs[inIdx]
                        val size = videoExtractor!!.readSampleData(buf, 0)
                        if (size < 0) {
                            videoDecoder!!.queueInputBuffer(inIdx, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            vDecDone = true
                            Log.i(TAG, "Video decoder input EOS, frames=$framesProcessed")
                        } else {
                            val pts = videoExtractor!!.sampleTime
                            videoDecoder!!.queueInputBuffer(inIdx, 0, size, pts, 0)
                            videoExtractor!!.advance()
                        }
                    }
                }

                // --- Drain video decoder → surface ---
                val decOut = videoDecoder!!.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (decOut >= 0) {
                    val render = bufferInfo.size > 0
                    // Release with render flag to push frame to encoder surface
                    videoDecoder!!.releaseOutputBuffer(decOut, render)
                    if (render) {
                        framesProcessed++
                        fpsFrameCount++
                        updateProgress(sourceDuration)
                    }
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        Log.i(TAG, "Video decoder output EOS, signaling encoder")
                        videoEncoder!!.signalEndOfInputStream()
                    }
                }

                // --- Drain video encoder → muxer ---
                if (!vEncDone) {
                    val encOut = videoEncoder!!.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    when {
                        encOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            muxerVideoTrack = muxer!!.addTrack(videoEncoder!!.outputFormat)
                            muxerVideoFormatReady = true
                            tryStartMuxer()
                        }
                        encOut >= 0 -> {
                            val buf = vEncOutputBufs[encOut]
                            if (bufferInfo.size > 0 && muxerStarted && muxerVideoTrack >= 0) {
                                buf.position(bufferInfo.offset)
                                buf.limit(bufferInfo.offset + bufferInfo.size)
                                muxer!!.writeSampleData(muxerVideoTrack, buf, bufferInfo)
                            }
                            videoEncoder!!.releaseOutputBuffer(encOut, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                vEncDone = true
                                Log.i(TAG, "Video encoder done")
                            }
                        }
                    }
                }

                // --- Audio processing ---
                if (audioDecoder != null && audioEncoder != null && audioExtractor != null) {
                    // Feed audio decoder
                    if (!aDecDone) {
                        val aInIdx = audioDecoder!!.dequeueInputBuffer(TIMEOUT_US)
                        if (aInIdx >= 0) {
                            val buf = aDecInputBufs!![aInIdx]
                            val size = audioExtractor.readSampleData(buf, 0)
                            if (size < 0) {
                                audioDecoder!!.queueInputBuffer(aInIdx, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                aDecDone = true
                            } else {
                                val pts = audioExtractor.sampleTime
                                audioDecoder!!.queueInputBuffer(aInIdx, 0, size, pts, 0)
                                audioExtractor.advance()
                            }
                        }
                    }

                    // Drain audio decoder → feed audio encoder
                    val aDecOut = audioDecoder!!.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    if (aDecOut >= 0) {
                        val pcmBuf = aDecOutputBufs!![aDecOut]
                        if (bufferInfo.size > 0) {
                            val aEncInIdx = audioEncoder!!.dequeueInputBuffer(TIMEOUT_US)
                            if (aEncInIdx >= 0) {
                                val encBuf = aEncInputBufs!![aEncInIdx]
                                encBuf.clear()
                                pcmBuf.position(bufferInfo.offset)
                                pcmBuf.limit(bufferInfo.offset + bufferInfo.size)
                                encBuf.put(pcmBuf)
                                val flags = if (aDecDone && bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                                audioEncoder!!.queueInputBuffer(aEncInIdx, 0, bufferInfo.size,
                                    bufferInfo.presentationTimeUs, flags)
                            }
                        }
                        audioDecoder!!.releaseOutputBuffer(aDecOut, false)
                    }

                    // Drain audio encoder → muxer
                    if (!aEncDone) {
                        val aEncOut = audioEncoder!!.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                        when {
                            aEncOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                if (audioEncoder != null) {
                                    muxerAudioTrack = muxer!!.addTrack(audioEncoder!!.outputFormat)
                                    muxerAudioFormatReady = true
                                    tryStartMuxer()
                                }
                            }
                            aEncOut >= 0 -> {
                                val buf = aEncOutputBufs!![aEncOut]
                                if (bufferInfo.size > 0 && muxerStarted && muxerAudioTrack >= 0) {
                                    buf.position(bufferInfo.offset)
                                    buf.limit(bufferInfo.offset + bufferInfo.size)
                                    muxer!!.writeSampleData(muxerAudioTrack, buf, bufferInfo)
                                }
                                audioEncoder!!.releaseOutputBuffer(aEncOut, false)
                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    aEncDone = true
                                    Log.i(TAG, "Audio encoder done")
                                }
                            }
                        }
                    }
                }
            }

            // ---- Finalize ----
            updateState(status = "Finalizing video file...")
            muxer?.stop()
            muxer?.release()
            muxer = null

            videoFd.close()

            val elapsed = System.currentTimeMillis() - startTimeMs
            val outputFile = File(outputPath)
            val fileSize = if (outputFile.exists()) outputFile.length() else 0L
            val durationMs = sourceDuration / 1000

            updateState(
                isProcessing = false,
                isComplete = true,
                progress = 1f,
                outputFilePath = outputPath,
                outputDurationMs = durationMs,
                outputFileSize = fileSize,
                status = "Complete!"
            )

            Log.i(TAG, "Done! ${elapsed}ms, size=${fileSize}, duration=${durationMs}ms")
            return@withContext Result.Success(outputPath, durationMs, fileSize)

        } catch (e: Exception) {
            Log.e(TAG, "Processing error", e)
            updateState(error = e.message, isProcessing = false, status = "Error occurred")
            return@withContext Result.Error(e.message ?: "Unknown processing error")
        } finally {
            runCatching { videoDecoder?.stop() }
            runCatching { videoDecoder?.release() }
            runCatching { videoEncoder?.stop() }
            runCatching { videoEncoder?.release() }
            runCatching { audioDecoder?.stop() }
            runCatching { audioDecoder?.release() }
            runCatching { audioEncoder?.stop() }
            runCatching { audioEncoder?.release() }
            runCatching { muxer?.release() }
            runCatching { videoExtractor?.release() }
            runCatching { audioExtractor?.release() }
        }
    }

    private fun updateProgress(sourceDuration: Long) {
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 500) {
            val currentFps = fpsFrameCount * 1000f / (now - lastFpsTime).coerceAtLeast(1)
            val elapsed = now - startTimeMs
            val progress = if (totalFrames > 0) {
                framesProcessed.toFloat() / totalFrames.toFloat()
            } else if (sourceDuration > 0) {
                // Use time-based progress
                val currentSec = elapsed / 1000f
                val totalSec = sourceDuration / 1_000_000f
                (currentSec / totalSec).coerceIn(0f, 1f)
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
