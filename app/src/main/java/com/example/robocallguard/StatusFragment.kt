package com.example.robocallguard

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton

class StatusFragment : Fragment(R.layout.fragment_status) {

    private lateinit var statusText: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        statusText = view.findViewById(R.id.status_text)
        view.findViewById<MaterialButton>(R.id.btn_role)
            .setOnClickListener { requestRole() }
        view.findViewById<MaterialButton>(R.id.btn_perms)
            .setOnClickListener { ensurePermissions() }

        refreshStatus()
        CallDataSyncService.schedulePeriodic(requireContext())
        Thread { ContactsHelper(requireContext().applicationContext).refresh() }.start()
    }

    override fun onResume() {
        super.onResume()
        if (::statusText.isInitialized) refreshStatus()
    }

    private fun refreshStatus() {
        val ctx = requireContext()
        val rm = ctx.getSystemService(RoleManager::class.java)
        val role = rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        val contacts = ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        val notif = Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val sms = ctx.checkSelfPermission(Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
        statusText.text = buildString {
            append("Screening role: ").append(if (role) "ACTIVE \u2714" else "NOT GRANTED \u26A0").append('\n')
            append("Contacts: ").append(if (contacts) "granted \u2714" else "missing \u26A0").append('\n')
            append("Notifications: ").append(if (notif) "granted \u2714" else "missing \u26A0").append('\n')
            append("SMS: ").append(if (sms) "granted \u2714" else "missing \u26A0")
        }
    }

    private fun ensurePermissions() {
        val ctx = requireContext()
        val needed = mutableListOf<String>()
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.READ_CONTACTS
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.POST_NOTIFICATIONS
        if (ctx.checkSelfPermission(Manifest.permission.RECEIVE_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.RECEIVE_SMS
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQ_PERMS)
        }
        refreshStatus()
    }

    private fun requestRole() {
        val rm = requireContext().getSystemService(RoleManager::class.java)
        startActivityForResult(
            rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), REQ_ROLE
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        refreshStatus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    companion object {
        private const val REQ_PERMS = 1002
        private const val REQ_ROLE = 1001
    }
}
