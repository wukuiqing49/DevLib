package com.wkq.photo.mediastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InternalArtifactStoreTest {
    @Test
    fun retentionLimitsCountAndTotalBytes() {
        val records = (1L..6L).map { index -> artifact(index, sizeBytes = 30L) }

        val retained = retainedArtifactIds(records, maxCount = 4, maxBytes = 100L)

        assertEquals(setOf("6", "5", "4"), retained)
    }

    @Test
    fun retentionKeepsNewestArtifactEvenWhenItExceedsBudget() {
        val records = listOf(artifact(2, sizeBytes = 200L), artifact(1, sizeBytes = 10L))

        val retained = retainedArtifactIds(records, maxCount = 10, maxBytes = 100L)

        assertEquals(setOf("2"), retained)
    }

    @Test
    fun retentionReturnsEmptySetForDisabledPolicy() {
        assertTrue(retainedArtifactIds(listOf(artifact(1, 1L)), maxCount = 0).isEmpty())
    }

    private fun artifact(createdAt: Long, sizeBytes: Long) = ArtifactEntity(
        id = createdAt.toString(),
        taskId = null,
        operationType = "test",
        kind = ArtifactKind.IMAGE.name,
        mimeType = "image/jpeg",
        displayName = "$createdAt.jpg",
        internalPath = "/artifacts/$createdAt.jpg",
        sizeBytes = sizeBytes,
        createdAtMillis = createdAt,
        mediaStoreUri = null,
        isPublished = false,
    )
}
