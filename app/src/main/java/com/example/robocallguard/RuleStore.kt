package com.example.robocallguard

import android.content.Context
import android.content.SharedPreferences

/** Persisted allow/block lists and per-list actions. */
class RuleStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("rules", Context.MODE_PRIVATE)

    var allowlist: Set<String>
        get() = prefs.getStringSet("allowlist", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("allowlist", v).apply()

    var blocklist: Set<String>
        get() = prefs.getStringSet("blocklist", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("blocklist", v).apply()

    var blockedPrefixes: List<String>
        get() = prefs.getString("prefixes", "")!!.split("|").filter { it.isNotBlank() }
        set(v) = prefs.edit().putString("prefixes", v.joinToString("|")).apply()

    var blockedPatterns: List<String>
        get() = prefs.getString("patterns", "")!!.split("|").filter { it.isNotBlank() }
        set(v) = prefs.edit().putString("patterns", v.joinToString("|")).apply()

    var blocklistAction: Action
        get() = actionOf(prefs.getString("blocklistAction", Action.REJECT.name))
        set(v) = prefs.edit().putString("blocklistAction", v.name).apply()

    var prefixAction: Action
        get() = actionOf(prefs.getString("prefixAction", Action.REJECT.name))
        set(v) = prefs.edit().putString("prefixAction", v.name).apply()

    var patternAction: Action
        get() = actionOf(prefs.getString("patternAction", Action.VOICEMAIL.name))
        set(v) = prefs.edit().putString("patternAction", v.name).apply()

    var voipAction: Action
        get() = actionOf(prefs.getString("voipAction", Action.REJECT.name))
        set(v) = prefs.edit().putString("voipAction", v.name).apply()

    var allowlistOnlyAction: Action
        get() = actionOf(prefs.getString("allowlistOnlyAction", Action.VOICEMAIL.name))
        set(v) = prefs.edit().putString("allowlistOnlyAction", v.name).apply()

    private fun actionOf(name: String?): Action = when (name) {
        "REJECT" -> Action.REJECT
        "VOICEMAIL" -> Action.VOICEMAIL
        "SILENCE" -> Action.SILENCE
        else -> Action.ALLOW
    }
}
