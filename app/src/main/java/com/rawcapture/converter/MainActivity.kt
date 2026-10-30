package com.rawcapture.converter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.rawcapture.converter.databinding.ActivityMainBinding
import com.rawcapture.converter.ui.fragments.*
import com.rawcapture.converter.utils.RecordingPreferences

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentVideoUri: Uri? = null
    private var selectedResolution: String = "1080p"
    private var selectedFps: Int = 30
    private var selectedBitrate: String = "Medium"
    private var selectedAspectRatio: String = "Original"
    private var audioEnabled: Boolean = true

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            showFragment(ImportFragment())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            checkPermissionsAndStart()
        }
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isEmpty()) {
            showFragment(HomeFragment())
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
            showFragment(HomeFragment())
        }
    }

    fun showFragment(fragment: Fragment, addToBackStack: Boolean = true) {
        val transaction = supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
        if (addToBackStack) {
            transaction.addToBackStack(null)
        }
        transaction.commit()
    }

    fun setVideoUri(uri: Uri) {
        currentVideoUri = uri
    }

    fun getVideoUri(): Uri? = currentVideoUri

    fun setResolution(resolution: String) { selectedResolution = resolution }
    fun getResolution(): String = selectedResolution

    fun setFps(fps: Int) { selectedFps = fps }
    fun getFps(): Int = selectedFps

    fun setBitrate(bitrate: String) { selectedBitrate = bitrate }
    fun getBitrate(): String = selectedBitrate

    fun setAspectRatio(ratio: String) { selectedAspectRatio = ratio }
    fun getAspectRatio(): String = selectedAspectRatio

    fun setAudioEnabled(enabled: Boolean) { audioEnabled = enabled }
    fun isAudioEnabled(): Boolean = audioEnabled

    fun startRecording() {
        val uri = currentVideoUri ?: return
        val intent = Intent(this, com.rawcapture.converter.recording.RecordingService::class.java).apply {
            putExtra("video_uri", uri.toString())
            putExtra("resolution", selectedResolution)
            putExtra("fps", selectedFps)
            putExtra("bitrate", selectedBitrate)
            putExtra("aspect_ratio", selectedAspectRatio)
            putExtra("audio_enabled", audioEnabled)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        showFragment(ProcessingFragment())
    }
}
