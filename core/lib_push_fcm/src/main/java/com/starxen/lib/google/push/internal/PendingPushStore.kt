package com.starxen.lib.google.push.internal

import com.starxen.lib.google.push.GooglePushMessage

internal class PendingPushStore(
    private val store: StringKeyValueStore,
    private val codec: GooglePushMessageCodec = GooglePushMessageCodec(),
    private val maxItems: Int = DEFAULT_MAX_ITEMS
) {

    @Synchronized
    fun enqueue(message: GooglePushMessage) {
        val messages = read()
        messages.removeAll { it.messageId == message.messageId }
        messages.add(message)
        while (messages.size > maxItems.coerceAtLeast(1)) {
            messages.removeAt(0)
        }
        write(messages)
    }

    @Synchronized
    fun peek(): GooglePushMessage? = read().firstOrNull()

    @Synchronized
    fun consume(): GooglePushMessage? {
        val messages = read()
        if (messages.isEmpty()) return null
        val message = messages.removeAt(0)
        write(messages)
        return message
    }

    @Synchronized
    fun all(): List<GooglePushMessage> = read().toList()

    @Synchronized
    fun clear() {
        store.remove(KEY_PENDING_CLICKS)
    }

    private fun read(): MutableList<GooglePushMessage> {
        return codec.decodeList(store.get(KEY_PENDING_CLICKS))
    }

    private fun write(messages: List<GooglePushMessage>) {
        if (messages.isEmpty()) {
            store.remove(KEY_PENDING_CLICKS)
        } else {
            store.put(KEY_PENDING_CLICKS, codec.encodeList(messages))
        }
    }

    companion object {
        private const val KEY_PENDING_CLICKS = "pending_push_clicks"
        private const val DEFAULT_MAX_ITEMS = 20
    }
}
