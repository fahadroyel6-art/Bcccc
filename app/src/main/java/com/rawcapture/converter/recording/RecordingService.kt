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
        const val ACTION_PAUSE = "com.rawcapture.converter.PAUSE"
        const val ACTION_RESUME = "com.rawcapture.converter.RESUME"

        // Singleton state for UI
        var currentState: VideoProcessingEngine.ProcessingState = VideoProcessingEngine.ProcessingState()
            private set
        var isServiceRunning = false
            private set

        fun startRecording(context: Context, uri: Uri, settings: RecordingSettings) {
            val intent = Intent(context, RecordingService::class.java).apply {
                putExtra("video_uri", uri.toString())
                putExtra("resolution", settings.resolution)
                putExtra("fps", settings.fps)
                putExtra("bitrate", settings.bitrate)
                putExtra("aspect_ratio", settings.aspectRatio)
                putExtra("audio_enabled", settings.audioEnabled)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    data class RecordingSettings(
        val resolution: String = "1080p",
        val fps: Int = 30,
        val bitrate: String = "Medium",
        val aspectRatio: String = "Original",
        val audioEnabled: Boolean = true
    )

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var engine: VideoProcessingEngine? = null
    private var isPaused = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            engine?.cancel()
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_PAUSE) {
            isPaused = true
            return START_STICKY
        }

        if (intent?.action == ACTION_RESUME) {
            isPaused = false
            return START_STICKY
        }

        // Start foreground notification
        startForeground(NOTIFICATION_ID, createNotification("Starting...", 0))

        // Acquire wake lock
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RawCapture::Processing").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L) // 4 hours max
        }

        val uriString = intent?.getStringExtra("video_uri") ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        val videoUri = Uri.parse(uriString)
        val resolution = intent.getStringExtra("resolution") ?: "1080p"
        val fps = intent.getIntExtra("fps", 30)
        val bitrate = intent.getStringExtra("bitrate") ?: "Medium"
        val aspectRatio = intent.getStringExtra("aspect_ratio") ?: "Original"
        val audioEnabled = intent.getBooleanExtra("audio_enabled", true)

        serviceScope.launch {
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
            else -> {
                bitrate.toIntOrNull() ?: 6_000_000
            }
        }

        // Apply aspect ratio
        val finalWidth: Int
        val finalHeight: Int
        when (aspectRatio) {
            "16:9" -> { finalWidth = targetWidth; finalHeight = targetHeight }
            "9:16" -> { finalWidth = targetHeight; finalHeight = targetWidth }
            "1:1" -> { finalWidth = targetHeight; finalHeight = targetHeight }
            else -> { finalWidth = targetWidth; finalHeight = targetHeight }
        }

        engine = VideoProcessingEngine(this, serviceScope)

        // Observe state for notifications
        serviceScope.launch {
            engine!!.state.collectLatest { state ->
                currentState = state
                updateNotification(state)
            }
        }

        val result = engine!!.process(
            sourceUri = uri,
            outputPath = tempFile.absolutePath,
            targetWidth = finalWidth,
            targetHeight = finalHeight,
            targetFps = fps,
            targetBitrate = bitrateValue,
            audioEnabled = audioEnabled
        )

        when (result) {
            is VideoProcessingEngine.Result.Success -> {
                // Save to gallery
                val timestamp = SimpleDateFormat("yyyy_MM_dd_HHmmss", Locale.getDefault()).format(Date())
                val displayName = "RawCapture_${timestamp}.mp4"
                val saved = MediaStoreManager.saveVideoToGallery(
                    context = this,
                    sourceFile = tempFile,
                    displayName = displayName
                )
                if (saved != null) {
                    Log.i(TAG, "Saved to gallery: $saved")
                    showCompletionNotification(displayName)
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

        val cancelIntent = PendingIntent.getBroadcast(
            this, 1,
            Intent(this, RecordingService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, App.CHANNEL_RECORDING)
            .setContentTitle("RawCapture")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_recording)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setProgress(100, progress, progress == 0)
            .addAction(R.drawable.ic_recording, "Cancel", cancelIntent)
            .build()
    }

    private fun updateNotification(state: VideoProcessingEngine.ProcessingState) {
        val progress = (state.progress * 100).toInt()
        val text = "${state.status} - $progress%"
        val notification = createNotification(text, progress)

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun showCompletionNotification(fileName: String) {
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
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    override fun onDestroy() {
        serviceScope.cancel()
        releaseWakeLock()
        isServiceRunning = false
        super.onDestroy()
    }
}
