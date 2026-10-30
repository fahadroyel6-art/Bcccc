package com.rawcapture.converter.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.rawcapture.converter.MainActivity
import com.rawcapture.converter.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private var selectedResolution = "1080p"
    private var selectedFps = 30
    private var selectedBitrate = "Medium"
    private var selectedAspectRatio = "Original"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val activity = activity as? MainActivity ?: return

        // Resolution chips
        binding.chipGroupResolution.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedResolution = when {
                checkedIds.contains(binding.chip480p.id) -> "480p"
                checkedIds.contains(binding.chip720p.id) -> "720p"
                checkedIds.contains(binding.chip1080p.id) -> "1080p"
                checkedIds.contains(binding.chip1440p.id) -> "1440p"
                checkedIds.contains(binding.chip4K.id) -> "4K"
                else -> "1080p"
            }
            activity.setResolution(selectedResolution)
        }

        // FPS chips
        binding.chipGroupFps.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedFps = when {
                checkedIds.contains(binding.chip24fps.id) -> 24
                checkedIds.contains(binding.chip30fps.id) -> 30
                checkedIds.contains(binding.chip60fps.id) -> 60
                else -> 30
            }
            activity.setFps(selectedFps)
        }

        // Bitrate chips
        binding.chipGroupBitrate.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedBitrate = when {
                checkedIds.contains(binding.chipLow.id) -> "Low"
                checkedIds.contains(binding.chipMedium.id) -> "Medium"
                checkedIds.contains(binding.chipHigh.id) -> "High"
                else -> "Medium"
            }
            activity.setBitrate(selectedBitrate)
        }

        // Aspect ratio chips
        binding.chipGroupAspect.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedAspectRatio = when {
                checkedIds.contains(binding.chipOriginal.id) -> "Original"
                checkedIds.contains(binding.chip16x9.id) -> "16:9"
                checkedIds.contains(binding.chip9x16.id) -> "9:16"
                checkedIds.contains(binding.chip1x1.id) -> "1:1"
                else -> "Original"
            }
            activity.setAspectRatio(selectedAspectRatio)
        }

        // Audio toggle
        binding.switchAudio.setOnCheckedChangeListener { _, isChecked ->
            activity.setAudioEnabled(isChecked)
        }

        // Start recording
        binding.btnStartRecording.setOnClickListener {
            activity.startRecording()
        }

        binding.btnBack.setOnClickListener {
            activity.onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
