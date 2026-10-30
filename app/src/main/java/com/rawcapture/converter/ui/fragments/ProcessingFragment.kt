package com.rawcapture.converter.ui.fragments

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.rawcapture.converter.MainActivity
import com.rawcapture.converter.recording.RecordingService
import com.rawcapture.converter.recording.VideoProcessingEngine
import com.rawcapture.converter.databinding.FragmentProcessingBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class ProcessingFragment : Fragment() {

    private var _binding: FragmentProcessingBinding? = null
    private val binding get() = _binding!!
    private var pulseAnimator: ObjectAnimator? = null
    private var observeJob: Job? = null
    private var completedNavigated = false
    private var errorShown = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentProcessingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        completedNavigated = false
        errorShown = false

        // Recording dot pulse animation
        pulseAnimator = ObjectAnimator.ofFloat(binding.recordingDot, "alpha", 1f, 0.3f).apply {
            duration = 800
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = LinearInterpolator()
            start()
        }

        // Check if service is running
        if (!RecordingService.isServiceRunning) {
            Log.w(TAG, "Service not running, starting recording")
            (activity as? MainActivity)?.startRecording()
        }

        // Poll RecordingService state for UI updates
        observeJob = viewLifecycleOwner.lifecycleScope.launch {
            var consecutiveCompleteChecks = 0
            
            while (isActive) {
                val state = RecordingService.currentState
                updateUI(state)

                // Check if complete - need 3 consecutive checks to avoid race condition
                if (state.isComplete && state.outputFilePath != null) {
                    consecutiveCompleteChecks++
                    if (consecutiveCompleteChecks >= 3 && !completedNavigated) {
                        completedNavigated = true
                        Log.i(TAG, "Processing complete, navigating")
                        navigateToCompleted(state)
                        break
                    }
                } else {
                    consecutiveCompleteChecks = 0
                }

                // Check for error - show once
                if (state.error != null && !errorShown) {
                    errorShown = true
                    binding.tvStatus.text = "Error: ${state.error}"
                    Log.e(TAG, "Processing error: ${state.error}")
                    // Don't navigate, let user see error and cancel
                }

                delay(100) // Poll every 100ms for smoother updates
            }
        }

        binding.btnCancel.setOnClickListener {
            observeJob?.cancel()
            
            // Send cancel to service
            try {
                val cancelIntent = Intent(requireContext(), RecordingService::class.java).apply {
                    action = RecordingService.ACTION_CANCEL
                }
                requireContext().startService(cancelIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send cancel", e)
            }

            val activity = activity as? MainActivity ?: return@setOnClickListener
            activity.supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
            activity.showFragment(HomeFragment(), addToBackStack = false)
        }
    }

    private fun updateUI(state: VideoProcessingEngine.ProcessingState) {
        _binding ?: return

        val percent = (state.progress * 100).toInt()
        binding.circularProgress.progress = percent
        binding.tvProgressPercent.text = "$percent%"
        binding.tvStatus.text = state.status
        binding.tvElapsed.text = formatTime(state.elapsedMs)
        binding.tvRemaining.text = if (state.remainingMs > 0) formatTime(state.remainingMs) else "--:--"
        binding.tvFps.text = String.format(Locale.getDefault(), "%.1f", state.currentFps)
        binding.tvOutputRes.text = state.outputResolution.ifEmpty { "1080p" }
    }

    private fun navigateToCompleted(state: VideoProcessingEngine.ProcessingState) {
        val activity = activity as? MainActivity ?: return
        activity.showFragment(CompletedFragment().apply {
            arguments = Bundle().apply {
                putString("output_path", state.outputFilePath)
                putLong("duration_ms", state.outputDurationMs)
                putLong("file_size", state.outputFileSize)
                putString("resolution", state.outputResolution)
            }
        })
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    override fun onDestroyView() {
        pulseAnimator?.cancel()
        observeJob?.cancel()
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "ProcessingFragment"
    }
}
