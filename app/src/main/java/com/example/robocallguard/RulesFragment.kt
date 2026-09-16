package com.example.robocallguard

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

class RulesFragment : Fragment(R.layout.fragment_rules) {

    private enum class ListKind { ALLOW, BLOCK, PREFIX, PATTERN }

    private lateinit var rules: RuleStore
    private lateinit var settings: SettingsStore
    private lateinit var topContainer: LinearLayout
    private lateinit var listHeader: TextView
    private lateinit var ruleInput: TextInputEditText
    private lateinit var ruleAdapter: RuleAdapter
    private var current: ListKind = ListKind.BLOCK

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        rules = RuleStore(ctx)
        settings = SettingsStore(ctx)
        topContainer = view.findViewById(R.id.rules_top)
        listHeader = view.findViewById(R.id.list_header)
        ruleInput = view.findViewById(R.id.rule_input)

        ruleAdapter = RuleAdapter(mutableListOf()) { removeEntry(it) }
        val rv = view.findViewById<RecyclerView>(R.id.rule_list)
        rv.layoutManager = LinearLayoutManager(ctx)
        rv.adapter = ruleAdapter

        buildTop()

        view.findViewById<MaterialButtonToggleGroup>(R.id.list_toggle)
            .addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (isChecked) {
                    current = when (checkedId) {
                        R.id.btn_allow -> ListKind.ALLOW
                        R.id.btn_prefix -> ListKind.PREFIX
                        R.id.btn_pattern -> ListKind.PATTERN
                        else -> ListKind.BLOCK
                    }
                    refreshList()
                }
            }

        view.findViewById<MaterialButton>(R.id.btn_add).setOnClickListener {
            val value = ruleInput.text?.toString()?.trim().orEmpty()
            if (value.isNotEmpty()) addEntry(value)
        }

        view.findViewById<MaterialButtonToggleGroup>(R.id.list_toggle)
            .check(R.id.btn_block)
        refreshList()
    }

    private fun buildTop() {
        topContainer.removeAllViews()

        addHeader("Behavior")
        addToggle("Allowlist-only mode (block unknown callers)",
            { settings.allowlistOnly }, { settings.allowlistOnly = it })
        addToggle("Auto-block VoIP numbers",
            { settings.voipBlock }, { settings.voipBlock = it })
        addToggle("Auto-allow contacts",
            { settings.contactsEnabled }, { settings.contactsEnabled = it })
        addToggle("Blocked-call notifications",
            { settings.notificationsEnabled }, { settings.notificationsEnabled = it })
        addToggle("SMS spam alerts",
            { settings.smsAlertsEnabled }, { settings.smsAlertsEnabled = it })
        addToggle("AI SMS check (server)",
            { settings.smsServerCheckEnabled }, { settings.smsServerCheckEnabled = it })
        addToggle("Server lookup for unknown numbers",
            { settings.lookupEnabled }, { settings.lookupEnabled = it })
        addToggle("Remote blocklist (server top-blocked)",
            { settings.remoteBlocklistEnabled }, { settings.remoteBlocklistEnabled = it })

        addHeader("Response per rule")
        addAction("Blocklist", { rules.blocklistAction }) { rules.blocklistAction = it }
        addAction("Prefix", { rules.prefixAction }) { rules.prefixAction = it }
        addAction("Regex pattern", { rules.patternAction }) { rules.patternAction = it }
        addAction("VoIP numbers", { rules.voipAction }) { rules.voipAction = it }
        addAction("Unknown (allowlist-only)", { rules.allowlistOnlyAction }) { rules.allowlistOnlyAction = it }

        addHeader("Your NPA-NXX (flags neighbor spoofing)")
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val npa = TextInputEditText(requireContext()).apply {
            hint = "e.g. 212555"
            setText(settings.myNpanxx)
            inputType = InputType.TYPE_CLASS_PHONE
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        row.addView(npa)
        row.addView(MaterialButton(requireContext()).apply {
            text = "Save"
            setOnClickListener {
                settings.myNpanxx = npa.text?.toString()?.trim().orEmpty()
                toast("Saved")
            }
        })
        topContainer.addView(row)
    }

    private fun addHeader(text: String) {
        topContainer.addView(TextView(requireContext()).apply {
            this.text = text
            textSize = 15f
            setPadding(0, dp(16), 0, dp(4))
        })
    }

    private fun addToggle(label: String, get: () -> Boolean, set: (Boolean) -> Unit) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(requireContext()).apply {
            text = label
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        row.addView(SwitchMaterial(requireContext()).apply {
            isChecked = get()
            setOnCheckedChangeListener { _, checked -> set(checked) }
        })
        topContainer.addView(row)
    }

    private fun addAction(name: String, get: () -> Action, set: (Action) -> Unit) {
        topContainer.addView(MaterialButton(requireContext()).apply {
            text = "$name: ${get().name} — tap to change"
            setOnClickListener {
                set(nextAction(get()))
                buildTop()
            }
        })
    }

    private fun nextAction(a: Action): Action = when (a) {
        Action.ALLOW -> Action.REJECT
        Action.REJECT -> Action.VOICEMAIL
        Action.VOICEMAIL -> Action.SILENCE
        Action.SILENCE -> Action.REJECT
    }

    private fun currentValues(): List<String> = when (current) {
        ListKind.ALLOW -> rules.allowlist.sorted()
        ListKind.BLOCK -> rules.blocklist.sorted()
        ListKind.PREFIX -> rules.blockedPrefixes
        ListKind.PATTERN -> rules.blockedPatterns
    }

    private fun addEntry(value: String) {
        when (current) {
            ListKind.ALLOW -> rules.allowlist = rules.allowlist + value
            ListKind.BLOCK -> rules.blocklist = rules.blocklist + value
            ListKind.PREFIX -> rules.blockedPrefixes = rules.blockedPrefixes + value
            ListKind.PATTERN -> rules.blockedPatterns = rules.blockedPatterns + value
        }
        ruleInput.text?.clear()
        refreshList()
    }

    private fun removeEntry(value: String) {
        when (current) {
            ListKind.ALLOW -> rules.allowlist = rules.allowlist - value
            ListKind.BLOCK -> rules.blocklist = rules.blocklist - value
            ListKind.PREFIX -> rules.blockedPrefixes = rules.blockedPrefixes - value
            ListKind.PATTERN -> rules.blockedPatterns = rules.blockedPatterns - value
        }
        refreshList()
    }

    private fun refreshList() {
        listHeader.text = when (current) {
            ListKind.ALLOW -> "Allowlist — always ring through"
            ListKind.BLOCK -> "Blocklist — exact numbers"
            ListKind.PREFIX -> "Blocked prefixes (e.g. 1800)"
            ListKind.PATTERN -> "Blocked regex patterns"
        }
        ruleAdapter.submit(currentValues())
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
}

class RuleAdapter(
    private val items: MutableList<String>,
    private val onRemove: (String) -> Unit
) : RecyclerView.Adapter<RuleAdapter.Holder>() {

    fun submit(values: List<String>) {
        items.clear()
        items.addAll(values)
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.rule_text)
        val delete: ImageButton = view.findViewById(R.id.btn_delete)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
        val v = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_rule, parent, false)
        return Holder(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val value = items[position]
        holder.text.text = value
        holder.delete.setOnClickListener { onRemove(value) }
    }
}
