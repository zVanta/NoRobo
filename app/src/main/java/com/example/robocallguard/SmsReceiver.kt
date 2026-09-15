package com.example.robocallguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * Observes incoming SMS and flags likely spam using local signals
 * (server-synced blocklist, neighbor spoofing) plus the server-side AI
 * text classifier. As a non-default SMS app we cannot silently drop
 * messages — we log and notify.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val sender = messages.firstOrNull()?.originatingAddress ?: return
        val body = messages.joinToString("") { it.messageBody ?: "" }

        val pending = goAsync()
        Thread {
            try {
                val settings = SettingsStore(context)
                val store = CallLogStore(context)
                val digits = sender.filter { it.isDigit() }.takeLast(10)
                val myNpa = settings.myNpanxx.filter { it.isDigit() }

                var flag = when {
                    store.isRemoteBlocked(digits) != null -> "remote-blocklist"
                    myNpa.length in 6..7 && digits.length == 10 &&
                        digits.startsWith(myNpa) -> "neighbor-spoof"
                    else -> "unknown"
                }

                var aiSpam = false
                if (settings.smsServerCheckEnabled && settings.baseUrl.isNotBlank()) {
                    aiSpam = LookupClient(settings.baseUrl, settings.token)
                        .checkSms(sender, body) == true
                    if (aiSpam && flag == "unknown") flag = "ai-spam"
                }

                store.logSms(sender, body, flag)

                if (flag != "unknown" && settings.smsAlertsEnabled) {
                    Notifications.smsAlert(context, sender, flag)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
