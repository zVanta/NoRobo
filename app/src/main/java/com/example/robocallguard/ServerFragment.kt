package com.example.robocallguard

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class ServerFragment : Fragment(R.layout.fragment_server) {

    private lateinit var settings: SettingsStore

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        settings = SettingsStore(requireContext())

        val url = view.findViewById<TextInputEditText>(R.id.url_input)
        val token = view.findViewById<TextInputEditText>(R.id.token_input)
        url.setText(settings.baseUrl)
        token.setText(settings.token)

        view.findViewById<MaterialButton>(R.id.btn_save).setOnClickListener {
            settings.baseUrl = url.text?.toString()?.trim().orEmpty()
            settings.token = token.text?.toString()?.trim().orEmpty()
            toast("Saved")
        }
        view.findViewById<MaterialButton>(R.id.btn_test).setOnClickListener { testLookup() }
    }

    private fun testLookup() {
        if (settings.baseUrl.isBlank()) {
            toast("Set the server URL first")
            return
        }
        Thread {
            val message = LookupClient(settings.baseUrl, settings.token)
                .probe("+15551234567")
            activity?.runOnUiThread { toast(message) }
        }.start()
    }

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
}
