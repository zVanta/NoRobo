package com.example.robocallguard

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private fun deviceId(context: Context): String =
    Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        ?: "unknown"

/**
 * Background sync: uploads pending call records, then refreshes the local
 * copy of the server's top-blocked feed so blocked numbers are caught
 * instantly without a network round-trip during screening.
 */
class CallDataSyncService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        Thread {
            try {
                val context = applicationContext
                val settings = SettingsStore(context)
                if (settings.baseUrl.isNotBlank() && settings.uploadEnabled) {
                    Uploader(context, settings.baseUrl, settings.token).uploadPending()
                    syncRemoteBlocklist(context, settings.baseUrl, settings.token)
                }
            } catch (e: Exception) {
                Log.e(TAG, "sync failed", e)
            }
            params?.let { jobFinished(it, false) }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    fun syncRemoteBlocklist(context: Context, baseUrl: String, token: String) {
        val url = URL(
            "${baseUrl.trimEnd('/')}/api/v1/top-blocked?token=" +
                URLEncoder.encode(token, "UTF-8")
        )
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            if (conn.responseCode !in 200..299) return
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val arr = json.optJSONArray("numbers") ?: return
            val entries = mutableListOf<Pair<String, Double>>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val number = obj.optString("number")
                val score = obj.optDouble("score", 0.0)
                if (number.length >= 7) entries.add(number to score)
            }
            CallLogStore(context).syncRemoteBlock(entries)
            Log.i(TAG, "synced ${entries.size} blocked numbers")
        } catch (e: Exception) {
            Log.w(TAG, "blocklist sync failed", e)
        } finally {
            conn.disconnect()
        }
    }

    class Uploader(
        private val context: Context,
        private val baseUrl: String,
        private val token: String
    ) {
        fun uploadPending() {
            val store = CallLogStore(context)
            val rows = store.pendingUploads(100)
            if (rows.isEmpty()) return

            val array = JSONArray()
            rows.forEach { r ->
                array.put(
                    JSONObject()
                        .put("number", r.number)
                        .put("timestamp", r.timestamp)
                        .put("action", r.action)
                        .put("reason", r.reason)
                        .put("carrier", r.carrier ?: JSONObject.NULL)
                        .put("lineType", r.lineType ?: JSONObject.NULL)
                        .put("spamScore", r.spamScore ?: JSONObject.NULL)
                        .put("business", r.business ?: JSONObject.NULL)
                )
            }
            val body = JSONObject()
                .put("token", token)
                .put("device", deviceId(context))
                .put("calls", array)
                .toString()

            val conn = URL("${baseUrl.trimEnd('/')}/api/v1/calls")
                .openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
                if (conn.responseCode in 200..299) {
                    store.markUploaded(rows.map { it.id })
                } else {
                    conn.errorStream?.close()
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    companion object {
        private const val TAG = "RobocallGuardSync"
        private const val PERIODIC_JOB = 101
        private const val ONCE_JOB = 102

        fun schedulePeriodic(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(PERIODIC_JOB) != null) return
            val info = JobInfo.Builder(
                PERIODIC_JOB,
                ComponentName(context, CallDataSyncService::class.java)
            )
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(6 * 60 * 60 * 1000L)
                .setPersisted(true)
                .build()
            scheduler.schedule(info)
        }

        fun scheduleOnce(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val info = JobInfo.Builder(
                ONCE_JOB,
                ComponentName(context, CallDataSyncService::class.java)
            )
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(5_000)
                .build()
            scheduler.schedule(info)
        }
    }
}
