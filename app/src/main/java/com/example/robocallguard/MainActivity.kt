package com.example.robocallguard

import android.app.Activity
import android.app.role.RoleManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 64, 64, 64)
        }

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
        }

        statusView = TextView(this).apply { textSize = 16f }

        val grantButton = Button(this).apply {
            text = "Grant Call Screening Role"
            setOnClickListener { requestScreeningRole() }
        }

        val explain = TextView(this).apply {
            text = "After granting, this app screens incoming calls and blocks " +
                "numbers matching your rules in CallRules.kt."
            textSize = 14f
        }

        layout.addView(title)
        layout.addView(statusView)
        layout.addView(grantButton)
        layout.addView(explain)
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val rm = getSystemService(RoleManager::class.java)
        val active = rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        statusView.text = "Status: ${if (active) "ACTIVE" else "NOT ACTIVE"}"
    }

    private fun requestScreeningRole() {
        val rm = getSystemService(RoleManager::class.java)
        startActivityForResult(
            rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), 1001
        )
    }
}
