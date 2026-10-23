package com.starxen.lib.google.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GooglePushMessageParserTest {

    @Test
    fun parsesDataPayloadAndPreservesBusinessFields() {
        val result = GooglePushMessageParser.parse(
            data = mapOf(
                "message_id" to "message-1",
                "event_type" to "friend_live",
                "title" to "Live now",
                "body" to "A friend started a live stream",
                "room_number" to "10086",
                "sent_time" to "1234",
                "ttl" to "60"
            )
        )

        assertTrue(result is GooglePushParseResult.Success)
        val message = (result as GooglePushParseResult.Success).message
        assertEquals("message-1", message.messageId)
        assertEquals("friend_live", message.eventType)
        assertEquals("Live now", message.title)
        assertEquals("10086", message.data["room_number"])
        assertEquals(1234L, message.sentTimeMillis)
        assertEquals(60, message.ttlSeconds)
    }

    @Test
    fun usesFirebaseFallbackFields() {
        val result = GooglePushMessageParser.parse(
            data = mapOf("type" to "system_message"),
            fallbackMessageId = "firebase-id",
            fallbackTitle = "System",
            fallbackBody = "Maintenance",
            fallbackSentTimeMillis = 99L,
            fallbackTtlSeconds = 10
        ) as GooglePushParseResult.Success

        assertEquals("firebase-id", result.message.messageId)
        assertEquals("system_message", result.message.eventType)
        assertEquals("System", result.message.title)
        assertEquals("Maintenance", result.message.body)
        assertEquals(99L, result.message.sentTimeMillis)
        assertEquals(10, result.message.ttlSeconds)
    }

    @Test
    fun allowsPayloadWithoutEventType() {
        val result = GooglePushMessageParser.parse(
            data = mapOf("message_id" to "message-1")
        )

        assertTrue(result is GooglePushParseResult.Success)
        val message = (result as GooglePushParseResult.Success).message
        assertEquals("message-1", message.messageId)
        assertEquals("", message.eventType)
    }

    @Test
    fun generatedMessageIdIsStableAcrossMapOrder() {
        val first = GooglePushMessageParser.parse(
            linkedMapOf(
                "event_type" to "private_message",
                "from_user_id" to "42",
                "body" to "hello"
            )
        ) as GooglePushParseResult.Success
        val second = GooglePushMessageParser.parse(
            linkedMapOf(
                "body" to "hello",
                "from_user_id" to "42",
                "event_type" to "private_message"
            )
        ) as GooglePushParseResult.Success
        val changed = GooglePushMessageParser.parse(
            mapOf(
                "event_type" to "private_message",
                "from_user_id" to "43",
                "body" to "hello"
            )
        ) as GooglePushParseResult.Success

        assertEquals(first.message.messageId, second.message.messageId)
        assertNotEquals(first.message.messageId, changed.message.messageId)
    }
}
