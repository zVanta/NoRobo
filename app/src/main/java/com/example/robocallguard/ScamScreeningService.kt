package com.example.robocallguard

import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log

/**
 * Screens every call to the native dialer. The user must grant this app the
 * "Caller ID & spam" role (see MainActivity) before onScreenCall fires.
 */
class ScamScreeningService : CallScreeningService() {

    override fun onScreenCall(details: Call.Details) {
        val number = details.handle?.schemeSpecificPart
        val verdict = CallRules.verdict(number)
        Log.i(TAG, "Screening $number -> $verdict")

        val builder = CallResponse.Builder()
        when (verdict) {
            CallRules.Verdict.ALLOW ->
                builder.setDisallowCall(false).setRejectCall(false)

            CallRules.Verdict.BLOCK ->
                builder.setDisallowCall(true).setRejectCall(true)

            // Default posture: let unknown callers through.
            // For "allowlist-only" mode, change this to reject unknown numbers.
            CallRules.Verdict.UNKNOWN ->
                builder.setDisallowCall(false).setRejectCall(false)
        }

        respondToCall(details, builder.build())
    }

    companion object { private const val TAG = "RobocallGuard" }
}
