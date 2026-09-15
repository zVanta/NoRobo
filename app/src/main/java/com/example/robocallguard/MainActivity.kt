package com.example.robocallguard

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var rules: RuleStore
    private lateinit var settings: SettingsStore
    private lateinit var logStore: CallLogStore

    private lateinit var rulesScroll: ScrollView
    private lateinit var rulesInner: LinearLayout
    private lateinit var serverScroll: ScrollView
    private lateinit var serverInner: LinearLayout
    private lateinit var logArea: LinearLayout
    private lateinit var logList: ListView

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rules = RuleStore(this)
        settings = SettingsStore(this)
        logStore = CallLogStore(this)
        Notifications.ensureChannel(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
        }

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
        }
        root.addView(title)

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.addView(Button(this).apply {
            text = "Rules & Status"
            setOnClickListener { showTab(rulesScroll) }
        })
        tabs.addView(Button(this).apply {
            text = "Server"
            setOnClickListener { showTab(serverScroll) }
        })
        tabs.addView(Button(this).apply {
            text = "Call Log"
            setOnClickListener { showTab(logArea) }
        })
        root.addView(tabs)

        rulesInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        rulesScroll = ScrollView(this).apply { addView(rulesInner) }

        serverInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        serverScroll = ScrollView(this).apply { addView(serverInner) }

        logList = ListView(this)
        logArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val actions = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            actions.addView(Button(this@MainActivity).apply {
                text = "Refresh"
                setOnClickListener { refreshLog() }
            })
            actions.addView(Button(this@MainActivity).apply {
                text = "Clear log"
                setOnClickListener {
                    logStore.clearAll()
                    refreshLog()
                }
            })
            addView(actions)
            addView(logList)
        }

        root.addView(rulesScroll)
        root.addView(serverScroll)
        root.addView(logArea)

        setContentView(root)
        rebuildRules()
        buildServer()
        showTab(rulesScroll)
        ensurePermissions()
        CallDataSyncService.schedulePeriodic(this)
    }

    override fun onResume() {
        super.onResume()
        rebuildRules()
        refreshLog()
    }

    private fun showTab(v: View) {
        rulesScroll.visibility = View.GONE
        serverScroll.visibility = View.GONE
        logArea.visibility = View.GONE
        v.visibility = View.VISIBLE
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(0, 16, 0, 4)
    }

    private fun statusText(): String {
        val rm = getSystemService(RoleManager::class.java)
        val role = rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        val contacts = checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        val notif = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return "Screening role: ${if (role) "ACTIVE" else "NOT GRANTED"}\n" +
            "Contacts permission: ${if (contacts) "granted" else "missing"}\n" +
            "Notifications permission: ${if (notif) "granted" else "missing"}"
    }

    private fun ensurePermissions() {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.READ_CONTACTS
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQ_PERMS)
        }
    }

    private fun requestScreeningRole() {
        val rm = getSystemService(RoleManager::class.java)
        startActivityForResult(
            rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), REQ_ROLE
        )
    }

    private fun rebuildRules() {
        rulesInner.removeAllViews()

        rulesInner.addView(label(statusText()).apply { textSize = 16f })

        rulesInner.addView(Button(this).apply {
            text = "Grant Call Screening Role"
            setOnClickListener { requestScreeningRole() }
        })
        rulesInner.addView(Button(this).apply {
            text = "Grant Android Permissions"
            setOnClickListener { ensurePermissions() }
        })
        rulesInner.addView(
            label(
                "Screens every call through the phone system in the background " +
                    "(no app UI needed). In-app VoIP calls (WhatsApp, Google Voice, " +
                    "etc.) are invisible to every screening app."
            )
        )

        addToggle(rulesInner, "Allowlist-only mode (block unknown callers)",
            { settings.allowlistOnly }, { settings.allowlistOnly = it })
        addToggle(rulesInner, "Auto-block VoIP numbers",
            { settings.voipBlock }, { settings.voipBlock = it })
        addToggle(rulesInner, "Auto-allow contacts",
            { settings.contactsEnabled }, { settings.contactsEnabled = it })
        addToggle(rulesInner, "Blocked-call notifications",
            { settings.notificationsEnabled }, { settings.notificationsEnabled = it })
        addToggle(rulesInner, "Server lookup for unknown numbers",
            { settings.lookupEnabled }, { settings.lookupEnabled = it })

        addActionButton(rulesInner, "Blocklist action", { rules.blocklistAction }) {
            rules.blocklistAction = it
        }
        addActionButton(rulesInner, "Prefix action", { rules.prefixAction }) {
            rules.prefixAction = it
        }
        addActionButton(rulesInner, "Pattern action", { rules.patternAction }) {
            rules.patternAction = it
        }
        addActionButton(rulesInner, "VoIP action", { rules.voipAction }) {
            rules.voipAction = it
        }
        addActionButton(rulesInner, "Unknown (allowlist-only) action", { rules.allowlistOnlyAction }) {
            rules.allowlistOnlyAction = it
        }

        rulesInner.addView(label("Allowlist (always ring through)"))
        addRuleList(rules.allowlist.sorted(),
            { v -> rules.allowlist = rules.allowlist + v; rebuildRules() },
            { v -> rules.allowlist = rules.allowlist - v; rebuildRules() }
        )

        rulesInner.addView(label("Blocklist (exact numbers)"))
        addRuleList(rules.blocklist.sorted(),
            { v -> rules.blocklist = rules.blocklist + v; rebuildRules() },
            { v -> rules.blocklist = rules.blocklist - v; rebuildRules() }
        )

        rulesInner.addView(label("Blocked prefixes (e.g. 1800)"))
        addRuleList(rules.blockedPrefixes,
            { v -> rules.blockedPrefixes = rules.blockedPrefixes + v; rebuildRules() },
            { v -> rules.blockedPrefixes = rules.blockedPrefixes - v; rebuildRules() }
        )

        rulesInner.addView(label("Blocked patterns (regex)"))
        addRuleList(rules.blockedPatterns,
            { v -> rules.blockedPatterns = rules.blockedPatterns + v; rebuildRules() },
            { v -> rules.blockedPatterns = rules.blockedPatterns - v; rebuildRules() }
        )
    }

    private fun addToggle(
        container: LinearLayout,
        text: String,
        get: () -> Boolean,
        set: (Boolean) -> Unit
    ) {
        container.addView(CheckBox(this).apply {
            this.text = text
            isChecked = get()
            setOnCheckedChangeListener { _, checked -> set(checked) }
        })
    }

    private fun addActionButton(
        container: LinearLayout,
        name: String,
        get: () -> Action,
        set: (Action) -> Unit
    ) {
        container.addView(Button(this).apply {
            text = "$name: ${get().name} (tap to change)"
            setOnClickListener {
                set(nextAction(get()))
                rebuildRules()
            }
        })
    }

    private fun addRuleList(
        values: List<String>,
        onAdd: (String) -> Unit,
        onRemove: (String) -> Unit
    ) {
        values.forEach { entry ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(TextView(this).apply {
                text = entry
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            row.addView(Button(this).apply {
                text = "x"
                setOnClickListener { onRemove(entry) }
            })
            rulesInner.addView(row)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val input = EditText(this).apply {
            hint = "add entry"
            inputType = InputType.TYPE_CLASS_PHONE
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        row.addView(input)
        row.addView(Button(this).apply {
            text = "Add"
            setOnClickListener {
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) onAdd(v)
            }
        })
        rulesInner.addView(row)
    }

    private fun nextAction(a: Action): Action = when (a) {
        Action.ALLOW -> Action.REJECT
        Action.REJECT -> Action.VOICEMAIL
        Action.VOICEMAIL -> Action.SILENCE
        Action.SILENCE -> Action.REJECT
    }

    private fun buildServer() {
        serverInner.removeAllViews()
        serverInner.addView(
            label("Backend URL — set at runtime, never stored in the repo")
        )
        val urlInput = EditText(this).apply {
            hint = "https://your-server.example.com"
            setText(settings.baseUrl)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        serverInner.addView(urlInput)

        serverInner.addView(label("API token"))
        val tokenInput = EditText(this).apply {
            hint = "shared token"
            setText(settings.token)
            inputType = InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        serverInner.addView(tokenInput)

        serverInner.addView(Button(this).apply {
            text = "Save server settings"
            setOnClickListener {
                settings.baseUrl = urlInput.text.toString().trim()
                settings.token = tokenInput.text.toString().trim()
                Toast.makeText(this@MainActivity, "Saved", Toast.LENGTH_SHORT).show()
            }
        })
        serverInner.addView(Button(this).apply {
            text = "Test lookup (+15551234567)"
            setOnClickListener { testLookup() }
        })
        serverInner.addView(
            label(
                "Lookups run inside the ~5s screening window and are cached " +
                    "locally. Spam/VoIP/business providers are configured " +
                    "server-side — the app never holds API keys."
            )
        )
    }

    private fun testLookup() {
        if (settings.baseUrl.isBlank()) {
            Toast.makeText(this, "Set the backend URL first", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            val result = LookupClient(settings.baseUrl, settings.token).lookup("+15551234567")
            runOnUiThread {
                Toast.makeText(
                    this,
                    "Lookup: ${result ?: "no response — check URL/token/server"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun refreshLog() {
        val rows = logStore.recent(100).map { r ->
            val t = timeFmt.format(Date(r.timestamp))
            val extra = listOfNotNull(
                r.lineType,
                r.business,
                r.spamScore?.let { "spam ${"%.2f".format(it)}" },
                r.carrier
            ).joinToString(" | ")
            "$t  ${r.number}\n    ${r.action} — ${r.reason}" +
                if (extra.isNotBlank()) "\n    $extra" else ""
        }
        logList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
    }

    companion object {
        private const val REQ_PERMS = 1002
        private const val REQ_ROLE = 1001
    }
}
