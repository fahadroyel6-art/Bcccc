package com.rawcapture.converter.ui.fragments

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.rawcapture.converter.MainActivity
import com.rawcapture.converter.databinding.FragmentPreviewBinding
import java.util.Locale

class PreviewFragment : Fragment() {

    private var _binding: FragmentPreviewBinding? = null
    private val binding get() = _binding!!
    private var player: ExoPlayer? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPreviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val activity = activity as? MainActivity ?: return
        val uri = activity.getVideoUri()

        if (uri == null) {
            activity.showFragment(HomeFragment())
            return
        }

        // Setup player
        player = ExoPlayer.Builder(requireContext()).build().also { exo ->
            binding.playerView.player = exo
            exo.setMediaItem(MediaItem.fromUri(uri))
            exo.prepare()
        }

        // Get video info
        val info = getVideoInfo(uri)
        binding.tvFileName.text = info.fileName
        binding.tvDuration.text = "Duration: ${info.duration}"
        binding.tvResolution.text = "Resolution: ${info.resolution}"
        binding.tvFileSize.text = "Size: ${info.fileSize}"

        binding.btnContinue.setOnClickListener {
            activity.showFragment(SettingsFragment())
        }

        binding.btnBack.setOnClickListener {
            player?.release()
            activity.showFragment(HomeFragment())
        }
    }

    private fun getVideoInfo(uri: Uri): VideoInfo {
        val resolver = requireContext().contentResolver
        var fileName = "Unknown"
        var fileSize = "Unknown"
        var duration = "Unknown"
        var resolution = "Unknown"

        try {
            val cursor = resolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIdx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (nameIdx >= 0) fileName = it.getString(nameIdx) ?: "Unknown"
                    if (sizeIdx >= 0) {
                        val sizeBytes = it.getLong(sizeIdx)
                        fileSize = formatFileSize(sizeBytes)
                    }
                }
            }

            // Get duration and resolution from MediaMetadataRetriever
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(requireContext(), uri)
            val durationMs = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull()
            if (durationMs != null) {
                duration = formatDuration(durationMs)
            }
            val width = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )
            val height = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )
            if (width != null && height != null) {
                resolution = "${width}x${height}"
            }
            retriever.release()
        } catch (e: Exception) {
            // Ignore metadata extraction errors
        }

        return VideoInfo(fileName, duration, resolution, fileSize)
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }

    data class VideoInfo(val fileName: String, val duration: String, val resolution: String, val fileSize: String)

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        player?.play()
    }

    override fun onDestroyView() {
        player?.release()
        player = null
        super.onDestroyView()
        _binding = null
    }
}
