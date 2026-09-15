package com.example.robocallguard

import android.content.Context
import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Screening pipeline:
 *   1. Local rules (allowlist, contacts, blocklist, prefixes, patterns) — instant.
 *   2. Cached server lookup, else a live lookup bounded to ~2.5s of the
 *      ~5s screening window.
 *   3. Log every call, notify on blocked calls, queue a background upload.
 */
class ScreeningEngine(context: Context) {

    private val appContext = context.applicationContext
    private val rules = RuleStore(appContext)
    private val settings = SettingsStore(appContext)
    private val logStore = CallLogStore(appContext)
    private val contacts = ContactsHelper(appContext)

    fun evaluateLocal(number: String?): Verdict {
        if (number.isNullOrBlank()) return Verdict(Action.ALLOW, "no number", "system")
        val digits = number.filter { it.isDigit() }

        if (Matchers.matchesExact(digits, rules.allowlist)) {
            return Verdict(Action.ALLOW, "allowlist", "rules")
        }
        if (settings.contactsEnabled && contacts.isInContacts(digits)) {
            return Verdict(Action.ALLOW, "contact", "contacts")
        }
        if (Matchers.matchesExact(digits, rules.blocklist)) {
            return Verdict(rules.blocklistAction, "blocklist", "rules")
        }
        Matchers.matchesPrefix(digits, rules.blockedPrefixes)?.let {
            return Verdict(rules.prefixAction, "prefix $it", "rules")
        }
        Matchers.matchesPattern(digits, number, rules.blockedPatterns)?.let {
            return Verdict(rules.patternAction, "pattern $it", "rules")
        }
        if (settings.allowlistOnly) {
            return Verdict(rules.allowlistOnlyAction, "unknown (allowlist-only)", "posture")
        }
        return Verdict(Action.ALLOW, "unknown", "posture")
    }

    fun screen(details: Call.Details): Verdict {
        val number = details.handle?.schemeSpecificPart.orEmpty()
        val digits = number.filter { it.isDigit() }

        var verdict = evaluateLocal(number)
        var lookup: LookupResult? = null

        val unknownPosture = verdict.source == "posture" && verdict.reason == "unknown"
        if (unknownPosture && settings.lookupEnabled && settings.baseUrl.isNotBlank()) {
            lookup = logStore.cached(digits.takeLast(10))
            if (lookup == null) {
                lookup = lookupWithTimeout(number)
                if (lookup != null) logStore.cache(digits.takeLast(10), lookup)
            }
            if (lookup != null) {
                val spam = lookup.spamScore
                verdict = when {
                    settings.voipBlock && lookup.isVoip ->
                        Verdict(rules.voipAction, "VoIP line type", "lookup")
                    spam != null && spam >= settings.spamThreshold.toDouble() ->
                        Verdict(Action.REJECT, "spam score $spam", "lookup")
                    else -> Verdict(Action.ALLOW, "lookup clean", "lookup")
                }
            }
        }

        logStore.log(
            number.ifBlank { "unknown" },
            verdict.action,
            "${verdict.source}: ${verdict.reason}",
            lookup
        )

        if (verdict.action != Action.ALLOW && settings.notificationsEnabled) {
            Notifications.blockedCall(
                appContext,
                number.ifBlank { "Unknown number" },
                verdict.reason
            )
        }

        CallDataSyncService.scheduleOnce(appContext)
        return verdict
    }

    fun responseFor(verdict: Verdict): CallScreeningService.CallResponse {
        val builder = CallScreeningService.CallResponse.Builder()
        when (verdict.action) {
            Action.ALLOW -> {
                builder.setDisallowCall(false).setRejectCall(false)
            }
            Action.REJECT -> {
                builder.setDisallowCall(true).setRejectCall(true)
            }
            Action.VOICEMAIL -> {
                builder.setDisallowCall(true).setRejectCall(false)
            }
            Action.SILENCE -> {
                builder.setDisallowCall(false).setRejectCall(false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    builder.setSilenceCall(true)
                }
            }
        }
        return builder.build()
    }

    private fun lookupWithTimeout(number: String): LookupResult? {
        val client = LookupClient(settings.baseUrl, settings.token)
        val result = AtomicReference<LookupResult?>(null)
        val done = CountDownLatch(1)
        val thread = Thread {
            try {
                result.set(client.lookup(number))
            } catch (e: Exception) {
                Log.w(TAG, "lookup failed", e)
            }
            done.countDown()
        }
        thread.start()
        try {
            done.await(2500, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        }
        return result.get()
    }

    companion object { private const val TAG = "RobocallGuard" }
}
