package com.rawcapture.converter.utils

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

object DeviceCapabilities {

    /**
     * Checks if device supports HEVC/H.265 hardware encoding
     */
    fun supportsHEVC(): Boolean {
        val codecs = MediaCodecList(MediaCodecList.ALL_CODECS)
        return codecs.codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals("video/hevc", ignoreCase = true) }
        }
    }

    /**
     * Checks if device supports a specific resolution for encoding
     */
    fun supportsResolution(width: Int, height: Int, mimeType: String = "video/avc"): Boolean {
        val codecs = MediaCodecList(MediaCodecList.ALL_CODECS)
        return codecs.codecInfos.any { info ->
            if (!info.isEncoder) return@any false
            val caps = info.getCapabilitiesForType(mimeType)
            val videoCaps = caps.videoCapabilities
            videoCaps.isSizeSupported(width, height)
        }
    }

    /**
     * Gets the maximum supported encoding resolution width
     */
    fun getMaxEncodingWidth(mimeType: String = "video/avc"): Int {
        val codecs = MediaCodecList(MediaCodecList.ALL_CODECS)
        var maxWidth = 1920
        for (info in codecs.codecInfos) {
            if (!info.isEncoder) continue
            try {
                val caps = info.getCapabilitiesForType(mimeType)
                val videoCaps = caps.videoCapabilities
                val w = videoCaps.supportedWidths.upper
                if (w > maxWidth) maxWidth = w
            } catch (_: Exception) {}
        }
        return maxWidth
    }

    /**
     * Checks if device supports 60fps encoding at given resolution
     */
    fun supports60fps(width: Int, height: Int): Boolean {
        val codecs = MediaCodecList(MediaCodecList.ALL_CODECS)
        return codecs.codecInfos.any { info ->
            if (!info.isEncoder) return@any false
            try {
                val caps = info.getCapabilitiesForType("video/avc")
                val videoCaps = caps.videoCapabilities
                videoCaps.areSizeAndRateSupported(width, height, 60.0)
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Gets available RAM in MB
     */
    fun getAvailableRamMB(): Long {
        return try {
            val runtime = Runtime.getRuntime()
            runtime.maxMemory() / (1024 * 1024)
        } catch (_: Exception) {
            4096
        }
    }

    /**
     * Returns a recommended output resolution based on device capabilities
     */
    fun getRecommendedResolution(): String {
        val maxW = getMaxEncodingWidth()
        return when {
            maxW >= 3840 -> "4K"
            maxW >= 2560 -> "1440p"
            maxW >= 1920 -> "1080p"
            maxW >= 1280 -> "720p"
            else -> "480p"
        }
    }

    /**
     * Returns device performance tier
     */
    fun getPerformanceTier(): PerformanceTier {
        val ram = getAvailableRamMB()
        val maxRes = getMaxEncodingWidth()
        return when {
            ram >= 6000 && maxRes >= 3840 -> PerformanceTier.HIGH
            ram >= 4000 && maxRes >= 1920 -> PerformanceTier.MEDIUM
            else -> PerformanceTier.LOW
        }
    }

    enum class PerformanceTier {
        LOW, MEDIUM, HIGH
    }
}
