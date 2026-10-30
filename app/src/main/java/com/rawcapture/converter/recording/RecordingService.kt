package com.rawcapture.converter.recording

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rawcapture.converter.App
import com.rawcapture.converter.MainActivity
import com.rawcapture.converter.R
import com.rawcapture.converter.storage.MediaStoreManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class RecordingService : Service() {

    companion object {
        private const val TAG = "RecordingService"
        const val NOTIFICATION_ID = 1001
        const val ACTION_CANCEL = "com.rawcapture.converter.CANCEL"

        // Shared state for UI
        var currentState: VideoProcessingEngine.ProcessingState = VideoProcessingEngine.ProcessingState()
            private set
        var isServiceRunning = false
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var processingJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action}")

        if (intent?.action == ACTION_CANCEL) {
            Log.i(TAG, "Cancel action received")
            processingJob?.cancel()
            stopSelf()
            return START_NOT_STICKY
        }

        // Start foreground notification IMMEDIATELY
        val startNotification = createNotification("Starting video conversion...", 0)
        try {
            startForeground(NOTIFICATION_ID, startNotification)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }

        // Acquire wake lock
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RawCapture::Processing").apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wake lock", e)
        }

        val uriString = intent?.getStringExtra("video_uri")
        if (uriString == null) {
            Log.e(TAG, "No video URI provided")
            stopSelf()
            return START_NOT_STICKY
        }

        val videoUri = Uri.parse(uriString)
        val resolution = intent.getStringExtra("resolution") ?: "1080p"
        val fps = intent.getIntExtra("fps", 30)
        val bitrate = intent.getStringExtra("bitrate") ?: "Medium"
        val aspectRatio = intent.getStringExtra("aspect_ratio") ?: "Original"
        val audioEnabled = intent.getBooleanExtra("audio_enabled", true)

        Log.i(TAG, "Starting processing: uri=$uriString, res=$resolution, fps=$fps, bitrate=$bitrate, audio=$audioEnabled")

        // Cancel any existing processing
        processingJob?.cancel()

        processingJob = serviceScope.launch {
            processVideo(videoUri, resolution, fps, bitrate, aspectRatio, audioEnabled)
        }

        return START_STICKY
    }

    private suspend fun processVideo(
        uri: Uri,
        resolution: String,
        fps: Int,
        bitrate: String,
        aspectRatio: String,
        audioEnabled: Boolean
    ) {
        val tempFile = File(cacheDir, "rawcapture_temp_${System.currentTimeMillis()}.mp4")
        Log.i(TAG, "Output temp file: ${tempFile.absolutePath}")

        val targetWidth: Int
        val targetHeight: Int
        when (resolution) {
            "480p" -> { targetWidth = 854; targetHeight = 480 }
            "720p" -> { targetWidth = 1280; targetHeight = 720 }
            "1080p" -> { targetWidth = 1920; targetHeight = 1080 }
            "1440p" -> { targetWidth = 2560; targetHeight = 1440 }
            "4K", "2160p" -> { targetWidth = 3840; targetHeight = 2160 }
            else -> { targetWidth = 1920; targetHeight = 1080 }
        }

        val bitrateValue: Int = when (bitrate) {
            "Low" -> 2_000_000
            "Medium" -> 6_000_000
            "High" -> 12_000_000
            else -> bitrate.toIntOrNull() ?: 6_000_000
        }

        val finalWidth: Int
        val finalHeight: Int
        when (aspectRatio) {
            "16:9" -> { finalWidth = targetWidth; finalHeight = targetHeight }
            "9:16" -> { finalWidth = targetHeight; finalHeight = targetWidth }
            "1:1" -> { finalWidth = targetHeight; finalHeight = targetHeight }
            else -> { finalWidth = targetWidth; finalHeight = targetHeight }
        }

        Log.i(TAG, "Final resolution: ${finalWidth}x${finalHeight}")

        // Create engine (no scope needed)
        val engine = VideoProcessingEngine(this)

        // Observe state for notifications
        val observeJob = serviceScope.launch {
            engine.state.collectLatest { state ->
                currentState = state
                updateNotification(state)
            }
        }

        val result = try {
            engine.process(
                sourceUri = uri,
                outputPath = tempFile.absolutePath,
                targetWidth = finalWidth,
                targetHeight = finalHeight,
                targetFps = fps,
                targetBitrate = bitrateValue,
                audioEnabled = audioEnabled
            )
        } catch (e: Exception) {
            Log.e(TAG, "Processing threw exception", e)
            VideoProcessingEngine.Result.Error(e.message ?: "Unknown error")
        } finally {
            observeJob.cancel()
        }

        when (result) {
            is VideoProcessingEngine.Result.Success -> {
                Log.i(TAG, "Processing success: ${result.outputPath}")
                val timestamp = SimpleDateFormat("yyyy_MM_dd_HHmmss", Locale.getDefault()).format(Date())
                val displayName = "RawCapture_${timestamp}.mp4"
                try {
                    val saved = MediaStoreManager.saveVideoToGallery(
                        context = this,
                        sourceFile = tempFile,
                        displayName = displayName
                    )
                    if (saved != null) {
                        Log.i(TAG, "Saved to gallery: $saved")
                        showCompletionNotification(displayName)
                    } else {
                        Log.e(TAG, "Failed to save to gallery")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Gallery save failed", e)
                }
                tempFile.delete()
            }
            is VideoProcessingEngine.Result.Error -> {
                Log.e(TAG, "Processing error: ${result.message}")
                tempFile.delete()
            }
            is VideoProcessingEngine.Result.Cancelled -> {
                Log.i(TAG, "Processing cancelled")
                tempFile.delete()
            }
        }

        releaseWakeLock()
        stopSelf()
        isServiceRunning = false
    }

    private fun createNotification(text: String, progress: Int): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, App.CHANNEL_RECORDING)
            .setContentTitle("RawCapture")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_recording)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setProgress(100, progress, progress == 0)
            .build()
    }

    private fun updateNotification(state: VideoProcessingEngine.ProcessingState) {
        val progress = (state.progress * 100).toInt()
        val text = "${state.status} - $progress%"
        val notification = createNotification(text, progress)

        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification", e)
        }
    }

    private fun showCompletionNotification(fileName: String) {
        try {
            val notification = NotificationCompat.Builder(this, App.CHANNEL_COMPLETE)
                .setContentTitle("Video Converted!")
                .setContentText("$fileName saved to Gallery")
                .setSmallIcon(R.drawable.ic_recording)
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ))
                .build()

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID + 1, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to show completion notification", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {}
        wakeLock = null
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        processingJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        isServiceRunning = false
        super.onDestroy()
    }
}
