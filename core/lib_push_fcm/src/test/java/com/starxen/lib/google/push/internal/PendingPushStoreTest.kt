package com.starxen.lib.google.push.internal

import com.starxen.lib.google.push.GooglePushMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingPushStoreTest {

    @Test
    fun consumesMessagesInFirstInFirstOutOrder() {
        val pendingStore = PendingPushStore(MemoryStringKeyValueStore())
        pendingStore.enqueue(message("message-1"))
        pendingStore.enqueue(message("message-2"))

        assertEquals("message-1", pendingStore.peek()?.messageId)
        assertEquals("message-1", pendingStore.consume()?.messageId)
        assertEquals("message-2", pendingStore.consume()?.messageId)
        assertNull(pendingStore.consume())
    }

    @Test
    fun duplicateMessageReplacesOldCopyAndMovesToEnd() {
        val pendingStore = PendingPushStore(MemoryStringKeyValueStore())
        pendingStore.enqueue(message("message-1", "old"))
        pendingStore.enqueue(message("message-2"))
        pendingStore.enqueue(message("message-1", "new"))

        val all = pendingStore.all()
        assertEquals(listOf("message-2", "message-1"), all.map { it.messageId })
        assertEquals("new", all.last().body)
    }

    @Test
    fun dropsOldestMessageBeyondCapacity() {
        val pendingStore = PendingPushStore(
            store = MemoryStringKeyValueStore(),
            maxItems = 2
        )
        pendingStore.enqueue(message("message-1"))
        pendingStore.enqueue(message("message-2"))
        pendingStore.enqueue(message("message-3"))

        assertEquals(listOf("message-2", "message-3"), pendingStore.all().map { it.messageId })
    }

    @Test
    fun corruptedStorageIsRecoveredOnNextEnqueue() {
        val storage = MemoryStringKeyValueStore()
        storage.put("pending_push_clicks", "not-json")
        val pendingStore = PendingPushStore(storage)

        assertNull(pendingStore.peek())
        pendingStore.enqueue(message("message-1"))
        assertEquals("message-1", pendingStore.consume()?.messageId)
    }

    private fun message(id: String, body: String = "body"): GooglePushMessage {
        return GooglePushMessage(
            messageId = id,
            eventType = "system_message",
            title = "title",
            body = body,
            data = mapOf("message_id" to id)
        )
    }
}
