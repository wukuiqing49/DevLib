package com.starxen.lib.google.push.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageDeduplicatorTest {

    @Test
    fun rejectsDuplicateInsideRetentionWindow() {
        var now = 1_000L
        val deduplicator = MessageDeduplicator(
            store = MemoryStringKeyValueStore(),
            timeProvider = TimeProvider { now },
            retentionMillis = 500L
        )

        assertTrue(deduplicator.shouldProcess("message-1"))
        now += 100L
        assertFalse(deduplicator.shouldProcess("message-1"))
    }

    @Test
    fun acceptsMessageAgainAfterRetentionWindow() {
        var now = 1_000L
        val deduplicator = MessageDeduplicator(
            store = MemoryStringKeyValueStore(),
            timeProvider = TimeProvider { now },
            retentionMillis = 500L
        )

        assertTrue(deduplicator.shouldProcess("message-1"))
        now += 500L
        assertTrue(deduplicator.shouldProcess("message-1"))
    }

    @Test
    fun evictsOldestMessageWhenCapacityIsReached() {
        var now = 1_000L
        val deduplicator = MessageDeduplicator(
            store = MemoryStringKeyValueStore(),
            timeProvider = TimeProvider { now },
            retentionMillis = 10_000L,
            maxItems = 2
        )

        assertTrue(deduplicator.shouldProcess("message-1"))
        now += 1L
        assertTrue(deduplicator.shouldProcess("message-2"))
        now += 1L
        assertTrue(deduplicator.shouldProcess("message-3"))
        assertTrue(deduplicator.shouldProcess("message-1"))
        assertFalse(deduplicator.shouldProcess("message-3"))
    }

    @Test
    fun rejectsBlankMessageId() {
        val deduplicator = MessageDeduplicator(MemoryStringKeyValueStore())
        assertFalse(deduplicator.shouldProcess(""))
    }
}
