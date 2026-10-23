package com.starxen.lib.google.push.internal

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

internal fun interface TimeProvider {
    fun nowMillis(): Long
}

internal class MessageDeduplicator(
    private val store: StringKeyValueStore,
    private val timeProvider: TimeProvider = TimeProvider { System.currentTimeMillis() },
    private val retentionMillis: Long = DEFAULT_RETENTION_MILLIS,
    private val maxItems: Int = DEFAULT_MAX_ITEMS,
    private val gson: Gson = Gson()
) {
    private val mapType = object : TypeToken<Map<String, Long>>() {}.type

    @Synchronized
    fun shouldProcess(messageId: String): Boolean {
        if (messageId.isBlank()) return false

        val now = timeProvider.nowMillis()
        val timestamps = read()
        timestamps.entries.removeAll { (_, timestamp) ->
            now - timestamp >= retentionMillis.coerceAtLeast(0L)
        }

        val existingTimestamp = timestamps[messageId]
        if (existingTimestamp != null && now - existingTimestamp < retentionMillis) {
            write(timestamps)
            return false
        }

        timestamps[messageId] = now
        while (timestamps.size > maxItems.coerceAtLeast(1)) {
            val oldestKey = timestamps.minByOrNull { it.value }?.key ?: break
            timestamps.remove(oldestKey)
        }
        write(timestamps)
        return true
    }

    @Synchronized
    fun clear() {
        store.remove(KEY_PROCESSED_MESSAGES)
    }

    private fun read(): MutableMap<String, Long> {
        val raw = store.get(KEY_PROCESSED_MESSAGES)
        if (raw.isNullOrBlank()) return mutableMapOf()
        return runCatching {
            gson.fromJson<Map<String, Long>>(raw, mapType)?.toMutableMap() ?: mutableMapOf()
        }.getOrDefault(mutableMapOf())
    }

    private fun write(timestamps: Map<String, Long>) {
        if (timestamps.isEmpty()) {
            store.remove(KEY_PROCESSED_MESSAGES)
        } else {
            store.put(KEY_PROCESSED_MESSAGES, gson.toJson(timestamps, mapType))
        }
    }

    companion object {
        private const val KEY_PROCESSED_MESSAGES = "processed_push_messages"
        private const val DEFAULT_RETENTION_MILLIS = 24 * 60 * 60 * 1000L
        private const val DEFAULT_MAX_ITEMS = 200
    }
}
