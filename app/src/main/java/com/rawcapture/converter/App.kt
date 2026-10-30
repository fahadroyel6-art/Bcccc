package com.rawcapture.converter

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val recordingChannel = NotificationChannel(
                CHANNEL_RECORDING,
                "Recording Processing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while converting video"
                setShowBadge(false)
            }

            val completionChannel = NotificationChannel(
                CHANNEL_COMPLETE,
                "Recording Complete",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notification when video conversion is complete"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(recordingChannel)
            manager.createNotificationChannel(completionChannel)
        }
    }

    companion object {
        const val CHANNEL_RECORDING = "recording_channel"
        const val CHANNEL_COMPLETE = "complete_channel"
        lateinit var instance: App
            private set
    }
}
