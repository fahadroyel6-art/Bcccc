package com.rawcapture.converter.ui.fragments

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.rawcapture.converter.MainActivity
import com.rawcapture.converter.databinding.FragmentImportBinding

class ImportFragment : Fragment() {

    private var _binding: FragmentImportBinding? = null
    private val binding get() = _binding!!

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                handleVideoSelected(uri)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentImportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBrowseGallery.setOnClickListener {
            openVideoPicker()
        }
    }

    private fun openVideoPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "video/mp4", "video/mkv", "video/quicktime",
                "video/x-msvideo", "video/webm", "video/3gpp", "video/*"
            ))
        }
        pickVideoLauncher.launch(intent)
    }

    private fun handleVideoSelected(uri: Uri) {
        val activity = activity as? MainActivity ?: return

        // Take persistable permission
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {}

        activity.setVideoUri(uri)
        activity.showFragment(PreviewFragment())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
