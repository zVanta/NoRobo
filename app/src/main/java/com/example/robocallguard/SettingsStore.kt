package com.example.robocallguard

import android.content.Context
import android.content.SharedPreferences

/**
 * App settings. Backend URL and token are entered by the user at runtime and
 * NEVER stored in the repository.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("baseUrl", "") ?: ""
        set(v) = prefs.edit().putString("baseUrl", v).apply()

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) = prefs.edit().putString("token", v).apply()

    var lookupEnabled: Boolean
        get() = prefs.getBoolean("lookupEnabled", false)
        set(v) = prefs.edit().putBoolean("lookupEnabled", v).apply()

    var uploadEnabled: Boolean
        get() = prefs.getBoolean("uploadEnabled", true)
        set(v) = prefs.edit().putBoolean("uploadEnabled", v).apply()

    var contactsEnabled: Boolean
        get() = prefs.getBoolean("contactsEnabled", true)
        set(v) = prefs.edit().putBoolean("contactsEnabled", v).apply()

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean("notificationsEnabled", true)
        set(v) = prefs.edit().putBoolean("notificationsEnabled", v).apply()

    var voipBlock: Boolean
        get() = prefs.getBoolean("voipBlock", true)
        set(v) = prefs.edit().putBoolean("voipBlock", v).apply()

    var allowlistOnly: Boolean
        get() = prefs.getBoolean("allowlistOnly", false)
        set(v) = prefs.edit().putBoolean("allowlistOnly", v).apply()

    var spamThreshold: Float
        get() = prefs.getFloat("spamThreshold", 0.7f)
        set(v) = prefs.edit().putFloat("spamThreshold", v).apply()

    /** Your own NPA-NXX (e.g. 212555) — used to flag neighbor spoofing. */
    var myNpanxx: String
        get() = prefs.getString("myNpanxx", "") ?: ""
        set(v) = prefs.edit().putString("myNpanxx", v).apply()

    var remoteBlocklistEnabled: Boolean
        get() = prefs.getBoolean("remoteBlocklistEnabled", true)
        set(v) = prefs.edit().putBoolean("remoteBlocklistEnabled", v).apply()

    var smsAlertsEnabled: Boolean
        get() = prefs.getBoolean("smsAlertsEnabled", true)
        set(v) = prefs.edit().putBoolean("smsAlertsEnabled", v).apply()
}
