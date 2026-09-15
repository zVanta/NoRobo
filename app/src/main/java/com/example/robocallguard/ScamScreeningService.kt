package com.example.robocallguard

import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log

/**
 * Screens every call routed through the telecom stack — the native dialer and
 * any app that places calls through the system. The system binds this service
 * automatically, so it runs in the background with no app UI open.
 *
 * The user must grant this app the "Caller ID & spam" role before
 * onScreenCall fires. In-app VoIP calls (WhatsApp, Google Voice, etc.) never
 * reach the telecom stack and cannot be seen by ANY screening app.
 */
class ScamScreeningService : CallScreeningService() {

    private val engine by lazy { ScreeningEngine(this) }

    override fun onScreenCall(details: Call.Details) {
        try {
            val verdict = engine.screen(details)
            Log.i(TAG, "Screening ${details.handle} -> ${verdict.action} (${verdict.reason})")
            respondToCall(details, engine.responseFor(verdict))
        } catch (e: Exception) {
            Log.e(TAG, "Screening failed", e)
            respondToCall(
                details,
                CallResponse.Builder()
                    .setDisallowCall(false)
                    .setRejectCall(false)
                    .build()
            )
        }
    }

    companion object { private const val TAG = "RobocallGuard" }
}
