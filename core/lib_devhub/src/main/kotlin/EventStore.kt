package com.devhub.sdk

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

internal class EventStore(context: Context, name: String, private val capacity: Int = 10_000, private val onDiscard: (Int) -> Unit = {}) : SQLiteOpenHelper(context, name, null, 2) {
  init { setWriteAheadLoggingEnabled(true) }
  override fun onCreate(database: SQLiteDatabase) {
    database.execSQL("CREATE TABLE events(sequence INTEGER PRIMARY KEY AUTOINCREMENT,event_id TEXT NOT NULL UNIQUE,payload TEXT NOT NULL)")
    database.execSQL("CREATE TABLE imports(source TEXT PRIMARY KEY)")
  }
  override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion < 2) {
      database.execSQL("ALTER TABLE events ADD COLUMN event_id TEXT")
      database.rawQuery("SELECT sequence,payload FROM events ORDER BY sequence", null).use { cursor ->
        val seen = mutableSetOf<String>()
        while (cursor.moveToNext()) {
          val sequence = cursor.getLong(0)
          val id = runCatching { JSONObject(cursor.getString(1)).getString("id") }.getOrNull()
          if (id == null || !seen.add(id)) database.delete("events", "sequence=?", arrayOf(sequence.toString()))
          else database.update("events", ContentValues().apply { put("event_id", id) }, "sequence=?", arrayOf(sequence.toString()))
        }
      }
      database.execSQL("CREATE UNIQUE INDEX idx_event_id ON events(event_id)")
      database.execSQL("CREATE TABLE imports(source TEXT PRIMARY KEY)")
    }
  }
  @Synchronized fun append(payload: String): Boolean {
    val id = JSONObject(payload).getString("id")
    if (readableDatabase.rawQuery("SELECT 1 FROM events WHERE event_id=?", arrayOf(id)).use { it.moveToFirst() }) return true
    if (count() >= capacity) return false
    writableDatabase.insertOrThrow("events", null, ContentValues().apply { put("event_id", id); put("payload", payload) })
    return true
  }
  @Synchronized fun count(): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM events", null).use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }
  @Synchronized fun batch(): List<Pair<Long, JSONObject>> {
    val invalid = mutableListOf<Long>()
    val batch = readableDatabase.rawQuery("SELECT sequence,CASE WHEN length(payload)<=16384 THEN payload END,event_id FROM events ORDER BY sequence LIMIT 20", null).use { cursor ->
      buildList {
        while (cursor.moveToNext()) {
          val sequence = cursor.getLong(0)
          val event = try {
            if (cursor.isNull(1)) throw IllegalArgumentException()
            JSONObject(cursor.getString(1)).also { require(it.getString("id") == cursor.getString(2)) }
          } catch (_: Exception) { invalid.add(sequence); null }
          if (event != null) add(sequence to event)
        }
      }
    }
    if (invalid.isNotEmpty()) {
      remove(invalid)
      try { onDiscard(invalid.size) } catch (_: Exception) { }
    }
    return batch
  }
  @Synchronized fun clear() { writableDatabase.delete("events", null, null) }
  @Synchronized fun remove(sequences: List<Long>) {
    if (sequences.isNotEmpty()) writableDatabase.delete("events", "sequence IN (${sequences.joinToString(",") { "?" }})", sequences.map(Long::toString).toTypedArray())
  }
  @Synchronized fun importOnce(source: String, events: JSONArray) {
    val database = writableDatabase
    database.beginTransaction()
    try {
      if (!database.rawQuery("SELECT 1 FROM imports WHERE source=?", arrayOf(source)).use { it.moveToFirst() }) {
        repeat(events.length()) { index ->
          val event = events.optJSONObject(index)
          if (event == null || !event.has("id") || event.optString("id").isBlank()) {
            try { onDiscard(1) } catch (_: Exception) { }
          } else if (!append(event.toString())) throw IllegalStateException("event_queue_full")
        }
        database.insertOrThrow("imports", null, ContentValues().apply { put("source", source) })
      }
      database.setTransactionSuccessful()
    } finally { database.endTransaction() }
  }
  @Synchronized override fun close() { super.close() }
}
