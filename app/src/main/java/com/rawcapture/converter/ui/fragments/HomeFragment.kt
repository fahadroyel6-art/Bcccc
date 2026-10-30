package com.rawcapture.converter.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.rawcapture.converter.MainActivity
import com.rawcapture.converter.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnImportVideo.setOnClickListener {
            (activity as MainActivity).showFragment(ImportFragment())
        }

        binding.btnSettings.setOnClickListener {
            (activity as MainActivity).showFragment(SettingsFragment())
        }

        binding.btnHistory.setOnClickListener {
            (activity as MainActivity).showFragment(HistoryFragment())
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
