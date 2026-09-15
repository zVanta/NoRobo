package com.example.robocallguard

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class LookupResult(
    val carrier: String? = null,
    val lineType: String? = null,
    val spamScore: Double? = null,
    val business: String? = null
) {
    val isVoip: Boolean
        get() = lineType?.lowercase()?.contains("voip") == true
}

data class CallRecord(
    val id: Long,
    val number: String,
    val timestamp: Long,
    val action: String,
    val reason: String,
    val carrier: String?,
    val lineType: String?,
    val spamScore: Double?,
    val business: String?,
    val uploaded: Int
)

data class SmsRecord(
    val sender: String,
    val body: String,
    val ts: Long,
    val flag: String
)

/** Local SQLite storage: every-call log + lookup cache. */
class CallLogStore(context: Context) :
    SQLiteOpenHelper(context, "calls.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE calls (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                number TEXT NOT NULL,
                ts INTEGER NOT NULL,
                action TEXT NOT NULL,
                reason TEXT,
                carrier TEXT,
                line_type TEXT,
                spam_score REAL,
                business TEXT,
                uploaded INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE lookup_cache (
                number TEXT PRIMARY KEY,
                carrier TEXT,
                line_type TEXT,
                spam_score REAL,
                business TEXT,
                checked_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE remote_block (
                number TEXT PRIMARY KEY,
                score REAL NOT NULL,
                added_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE sms (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sender TEXT NOT NULL,
                body TEXT,
                ts INTEGER NOT NULL,
                flag TEXT NOT NULL)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS remote_block (
                    number TEXT PRIMARY KEY,
                    score REAL NOT NULL,
                    added_at INTEGER NOT NULL)"""
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS sms (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    sender TEXT NOT NULL,
                    body TEXT,
                    ts INTEGER NOT NULL,
                    flag TEXT NOT NULL)"""
            )
        }
    }

    fun log(number: String, action: Action, reason: String, lookup: LookupResult? = null) {
        val cv = ContentValues().apply {
            put("number", number)
            put("ts", System.currentTimeMillis())
            put("action", action.name)
            put("reason", reason)
            put("carrier", lookup?.carrier)
            put("line_type", lookup?.lineType)
            put("spam_score", lookup?.spamScore)
            put("business", lookup?.business)
            put("uploaded", 0)
        }
        val db = writableDatabase
        db.insert("calls", null, cv)
        prune(db)
    }

    fun cached(number: String): LookupResult? {
        readableDatabase.query(
            "lookup_cache", null, "number = ?",
            arrayOf(number), null, null, null
        ).use { c ->
            if (c.moveToFirst()) {
                val i = { col: String -> c.getColumnIndexOrThrow(col) }
                return LookupResult(
                    carrier = c.getString(i("carrier")),
                    lineType = c.getString(i("line_type")),
                    spamScore = if (c.isNull(i("spam_score"))) null else c.getDouble(i("spam_score")),
                    business = c.getString(i("business"))
                )
            }
        }
        return null
    }

    fun cache(number: String, lookup: LookupResult) {
        val cv = ContentValues().apply {
            put("number", number)
            put("carrier", lookup.carrier)
            put("line_type", lookup.lineType)
            put("spam_score", lookup.spamScore)
            put("business", lookup.business)
            put("checked_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "lookup_cache", null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun pendingUploads(limit: Int): List<CallRecord> {
        val out = mutableListOf<CallRecord>()
        readableDatabase.query(
            "calls", null, "uploaded = 0", null, null, null, "id ASC", limit.toString()
        ).use { c -> readAll(c, out) }
        return out
    }

    fun recent(limit: Int): List<CallRecord> {
        val out = mutableListOf<CallRecord>()
        readableDatabase.query(
            "calls", null, null, null, null, null, "id DESC", limit.toString()
        ).use { c -> readAll(c, out) }
        return out
    }

    private fun readAll(c: android.database.Cursor, out: MutableList<CallRecord>) {
        val i = { col: String -> c.getColumnIndexOrThrow(col) }
        while (c.moveToNext()) {
            out.add(
                CallRecord(
                    id = c.getLong(i("id")),
                    number = c.getString(i("number")),
                    timestamp = c.getLong(i("ts")),
                    action = c.getString(i("action")),
                    reason = c.getString(i("reason")) ?: "",
                    carrier = c.getString(i("carrier")),
                    lineType = c.getString(i("line_type")),
                    spamScore = if (c.isNull(i("spam_score"))) null else c.getDouble(i("spam_score")),
                    business = c.getString(i("business")),
                    uploaded = c.getInt(i("uploaded"))
                )
            )
        }
    }

    fun markUploaded(ids: List<Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { id ->
                val cv = ContentValues().apply { put("uploaded", 1) }
                db.update("calls", cv, "id = ?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearAll() {
        writableDatabase.delete("calls", null, null)
    }

    // ---- Remote blocklist (synced from the server) ------------------------

    fun isRemoteBlocked(number: String): Double? {
        readableDatabase.query(
            "remote_block", null, "number = ?",
            arrayOf(number), null, null, null
        ).use { c ->
            if (c.moveToFirst()) {
                return c.getDouble(c.getColumnIndexOrThrow("score"))
            }
        }
        return null
    }

    fun syncRemoteBlock(entries: List<Pair<String, Double>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("remote_block", null, null)
            entries.forEach { (number, score) ->
                val cv = ContentValues().apply {
                    put("number", number)
                    put("score", score)
                    put("added_at", System.currentTimeMillis())
                }
                db.insert("remote_block", null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---- SMS log ------------------------------------------------------------

    fun logSms(sender: String, body: String, flag: String) {
        val cv = ContentValues().apply {
            put("sender", sender)
            put("body", body)
            put("ts", System.currentTimeMillis())
            put("flag", flag)
        }
        val db = writableDatabase
        db.insert("sms", null, cv)
        prune(db)
    }

    /** Bound local storage: keep the newest 2000 calls / 500 SMS. */
    private fun prune(db: SQLiteDatabase) {
        try {
            db.execSQL(
                "DELETE FROM calls WHERE id NOT IN (SELECT id FROM calls ORDER BY id DESC LIMIT 2000)"
            )
            db.execSQL(
                "DELETE FROM sms WHERE id NOT IN (SELECT id FROM sms ORDER BY id DESC LIMIT 500)"
            )
        } catch (_: Exception) {
            // Best-effort pruning — never break call screening for storage.
        }
    }

    fun recentSms(limit: Int): List<SmsRecord> {
        val out = mutableListOf<SmsRecord>()
        readableDatabase.query(
            "sms", null, null, null, null, null, "id DESC", limit.toString()
        ).use { c ->
            val i = { col: String -> c.getColumnIndexOrThrow(col) }
            while (c.moveToNext()) {
                out.add(
                    SmsRecord(
                        sender = c.getString(i("sender")),
                        body = c.getString(i("body")) ?: "",
                        ts = c.getLong(i("ts")),
                        flag = c.getString(i("flag"))
                    )
                )
            }
        }
        return out
    }
}
