package com.rawcapture.converter.ui.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.rawcapture.converter.MainActivity
import com.rawcapture.converter.R
import com.rawcapture.converter.databinding.FragmentCompletedBinding
import java.io.File
import java.util.Locale

class CompletedFragment : Fragment() {

    private var _binding: FragmentCompletedBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCompletedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val outputPath = arguments?.getString("output_path") ?: ""
        val durationMs = arguments?.getLong("duration_ms") ?: 0L
        val fileSize = arguments?.getLong("file_size") ?: 0L

        binding.tvOutputDuration.text = formatDuration(durationMs)
        binding.tvOutputSize.text = formatFileSize(fileSize)
        binding.tvOutputResolution.text = arguments?.getString("resolution") ?: "1080p"

        binding.btnPlayVideo.setOnClickListener {
            playVideo(outputPath)
        }

        binding.btnShare.setOnClickListener {
            shareVideo(outputPath)
        }

        binding.btnOpenGallery.setOnClickListener {
            openGallery()
        }

        binding.btnNewRecording.setOnClickListener {
            val activity = activity as? MainActivity ?: return@setOnClickListener
            // Clear back stack
            activity.supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
            activity.showFragment(HomeFragment(), addToBackStack = false)
        }
    }

    private fun playVideo(path: String) {
        try {
            val file = File(path)
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Try with content URI from MediaStore
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(path), "video/*")
            }
            startActivity(intent)
        }
    }

    private fun shareVideo(path: String) {
        try {
            val file = File(path)
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share Video"))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun openGallery() {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                type = "video/*"
            }
            startActivity(intent)
        } catch (_: Exception) {}
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
