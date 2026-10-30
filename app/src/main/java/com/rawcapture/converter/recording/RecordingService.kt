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

        // Shared state
        @Volatile
        var currentState: VideoProcessingEngine.ProcessingState = VideoProcessingEngine.ProcessingState()
            private set
        
        @Volatile
        var isServiceRunning = false
            private set
        
        @Volatile
        var processingComplete = false
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var processingJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        processingComplete = false
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

        // Start foreground notification
        val startNotification = createNotification("Starting...", 0)
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

        Log.i(TAG, "Starting: res=$resolution, fps=$fps, bitrate=$bitrate")

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
        Log.i(TAG, "Output: ${tempFile.absolutePath}")

        var targetWidth: Int
        var targetHeight: Int
        when (resolution) {
            "480p" -> { targetWidth = 854; targetHeight = 480 }
            "720p" -> { targetWidth = 1280; targetHeight = 720 }
            "1080p" -> { targetWidth = 1920; targetHeight = 1080 }
            "1440p" -> { targetWidth = 2560; targetHeight = 1440 }
            "4K", "2160p" -> { targetWidth = 3840; targetHeight = 2160 }
            else -> { targetWidth = 1920; targetHeight = 1080 }
        }

        // Handle aspect ratio
        when (aspectRatio) {
            "9:16" -> {
                val temp = targetWidth
                targetWidth = targetHeight
                targetHeight = temp
            }
            "1:1" -> {
                targetWidth = targetHeight
            }
        }

        val bitrateValue: Int = when (bitrate) {
            "Low" -> 2_000_000
            "Medium" -> 6_000_000
            "High" -> 12_000_000
            else -> bitrate.toIntOrNull() ?: 6_000_000
        }

        Log.i(TAG, "Final: ${targetWidth}x${targetHeight} @ ${fps}fps, bitrate=$bitrateValue")

        val engine = VideoProcessingEngine(this)

        // Observe state for notifications
        val observeJob = serviceScope.launch {
            engine.state.collectLatest { state ->
                currentState = state
                updateNotification(state)
                
                // Log completion
                if (state.isComplete) {
                    Log.i(TAG, "Processing complete!")
                    processingComplete = true
                }
            }
        }

        val result = try {
            engine.process(
                sourceUri = uri,
                outputPath = tempFile.absolutePath,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                targetFps = fps,
                targetBitrate = bitrateValue,
                audioEnabled = audioEnabled
            )
        } catch (e: Exception) {
            Log.e(TAG, "Processing exception", e)
            VideoProcessingEngine.Result.Error(e.message ?: "Unknown error")
        } finally {
            observeJob.cancel()
        }

        when (result) {
            is VideoProcessingEngine.Result.Success -> {
                Log.i(TAG, "Success: ${result.outputPath}")
                val timestamp = SimpleDateFormat("yyyy_MM_dd_HHmmss", Locale.getDefault()).format(Date())
                val displayName = "RawCapture_${timestamp}.mp4"
                try {
                    val saved = MediaStoreManager.saveVideoToGallery(
                        context = this,
                        sourceFile = tempFile,
                        displayName = displayName
                    )
                    if (saved != null) {
                        Log.i(TAG, "Saved: $saved")
                        showCompletionNotification(displayName)
                    } else {
                        Log.e(TAG, "Save failed")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Gallery save failed", e)
                }
                tempFile.delete()
            }
            is VideoProcessingEngine.Result.Error -> {
                Log.e(TAG, "Error: ${result.message}")
                tempFile.delete()
            }
            is VideoProcessingEngine.Result.Cancelled -> {
                Log.i(TAG, "Cancelled")
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
            Log.w(TAG, "Notification update failed", e)
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
            Log.w(TAG, "Completion notification failed", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
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
