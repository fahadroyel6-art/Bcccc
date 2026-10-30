package com.rawcapture.converter.utils

import android.content.Context
import android.content.SharedPreferences

object RecordingPreferences {
    private const val PREFS_NAME = "raw_capture_prefs"
    private const val KEY_RESOLUTION = "resolution"
    private const val KEY_FPS = "fps"
    private const val KEY_BITRATE = "bitrate"
    private const val KEY_ASPECT_RATIO = "aspect_ratio"
    private const val KEY_AUDIO = "audio_enabled"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getResolution(context: Context): String = getPrefs(context).getString(KEY_RESOLUTION, "1080p") ?: "1080p"
    fun setResolution(context: Context, value: String) = getPrefs(context).edit().putString(KEY_RESOLUTION, value).apply()

    fun getFps(context: Context): Int = getPrefs(context).getInt(KEY_FPS, 30)
    fun setFps(context: Context, value: Int) = getPrefs(context).edit().putInt(KEY_FPS, value).apply()

    fun getBitrate(context: Context): String = getPrefs(context).getString(KEY_BITRATE, "Medium") ?: "Medium"
    fun setBitrate(context: Context, value: String) = getPrefs(context).edit().putString(KEY_BITRATE, value).apply()

    fun getAspectRatio(context: Context): String = getPrefs(context).getString(KEY_ASPECT_RATIO, "Original") ?: "Original"
    fun setAspectRatio(context: Context, value: String) = getPrefs(context).edit().putString(KEY_ASPECT_RATIO, value).apply()

    fun isAudioEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_AUDIO, true)
    fun setAudioEnabled(context: Context, value: Boolean) = getPrefs(context).edit().putBoolean(KEY_AUDIO, value).apply()
}
