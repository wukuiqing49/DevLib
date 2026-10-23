package com.wkq.dev.metadata.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataTemplateWorkflowTest {

    @Test
    fun directProcessWithRequiredPerPhotoFieldStillOpensWorkbench() {
        val template = MetadataTemplate(
            id = "test",
            workflowPostCaptureAction = MetadataTemplate.WORKFLOW_ACTION_DIRECT_PROCESS,
            workflowRequiredFields = listOf("headline"),
            workflowPerPhotoFields = listOf("headline")
        )

        assertTrue(template.shouldOpenEditorAfterCapture())
    }

    @Test
    fun directProcessWithoutRequiredPerPhotoFieldCanRemainDirect() {
        val template = MetadataTemplate(
            id = "test",
            workflowPostCaptureAction = MetadataTemplate.WORKFLOW_ACTION_DIRECT_PROCESS,
            workflowRequiredFields = emptyList(),
            workflowPerPhotoFields = listOf("headline")
        )

        assertFalse(template.shouldOpenEditorAfterCapture())
    }

    @Test
    fun versionTwoKeepsExplicitlyEmptyRuntimeFields() {
        val template = MetadataTemplate(
            id = "automatic",
            workflowVersion = 2,
            workflowBatchFields = emptyList(),
            workflowPerPhotoFields = emptyList()
        )

        assertTrue(template.resolvedBatchFields().isEmpty())
        assertTrue(template.resolvedPerPhotoFields().isEmpty())
    }
}
