package com.example.robocallguard

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Calls the self-hosted backend:
 *   POST {base}/api/v1/lookup  {"number": "+1...", "token": "..."}
 * Expects JSON: {"carrier": ..., "lineType": ..., "spamScore": ..., "business": ...}
 * All keys and provider config live server-side.
 */
class LookupClient(private val baseUrl: String, private val token: String) {

    fun lookup(number: String): LookupResult? {
        val url = URL("${baseUrl.trimEnd('/')}/api/v1/lookup")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 2200
            conn.readTimeout = 2200
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val body = JSONObject()
                .put("number", number)
                .put("token", token)
                .toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode !in 200..299) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            return LookupResult(
                carrier = json.optString("carrier").takeIf { it.isNotBlank() },
                lineType = json.optString("lineType").takeIf { it.isNotBlank() },
                spamScore = if (json.has("spamScore")) json.optDouble("spamScore") else null,
                business = json.optString("business").takeIf { it.isNotBlank() }
            )
        } catch (_: Exception) {
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** POST /api/v1/sms/check — AI spam flag, or null on failure. */
    fun checkSms(sender: String, text: String): Boolean? {
        val url = URL("${baseUrl.trimEnd('/')}/api/v1/sms/check")
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val body = JSONObject()
                .put("sender", sender)
                .put("text", text)
                .put("token", token)
                .toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode !in 200..299) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            json.optBoolean("spam", false)
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** POST /api/v1/sms/report — label this SMS as spam for future training. */
    fun reportSms(sender: String, text: String): Boolean {
        val url = URL("${baseUrl.trimEnd('/')}/api/v1/sms/report")
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val body = JSONObject()
                .put("sender", sender)
                .put("text", text)
                .put("token", token)
                .toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    /** POST /api/v1/report — community spam report. */
    fun report(number: String, category: String): Boolean {
        val url = URL("${baseUrl.trimEnd('/')}/api/v1/report")
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val body = JSONObject()
                .put("number", number)
                .put("category", category)
                .put("token", token)
                .toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }
}
