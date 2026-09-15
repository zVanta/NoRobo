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

/** Local SQLite storage: every-call log + lookup cache. */
class CallLogStore(context: Context) :
    SQLiteOpenHelper(context, "calls.db", null, 1) {

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
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("DROP TABLE IF EXISTS calls")
        db.execSQL("DROP TABLE IF EXISTS lookup_cache")
        onCreate(db)
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
        writableDatabase.insert("calls", null, cv)
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
}
