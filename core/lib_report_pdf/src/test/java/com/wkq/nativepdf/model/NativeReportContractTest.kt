package com.wkq.nativepdf.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeReportContractTest {

    @Test
    fun `简洁归档模板保留问题汇总`() {
        assertTrue(
            NativeReportTemplate.simpleSectionOrder().contains(NativeReportSectionType.ISSUE_SUMMARY)
        )
    }

    @Test
    fun `默认章节顺序覆盖完整报告链路`() {
        assertEquals(
            listOf(
                NativeReportSectionType.COVER,
                NativeReportSectionType.SUMMARY,
                NativeReportSectionType.ISSUE_SUMMARY,
                NativeReportSectionType.INSPECTION_DETAILS,
                NativeReportSectionType.PHOTO_EVIDENCE,
                NativeReportSectionType.SIGNATURE
            ),
            NativeReportSectionType.defaultOrder()
        )
    }

    @Test
    fun `报告数据默认集合为空且页码格式保留占位符`() {
        val data = NativeReportData(
            reportId = "SR-1",
            title = "Inspection",
            projectName = "Project"
        )

        assertTrue(data.items.isEmpty())
        assertTrue(data.photos.isEmpty())
        assertTrue(data.signatures.isEmpty())
        assertEquals("Page 3", String.format(data.labels.pageNumberFormat, 3))
    }

    @Test
    fun `国际签名资料保留完整长姓名职位和公司名称`() {
        val signature = NativeReportSignature(
            role = "Senior Environmental Compliance and Quality Assurance Inspector",
            signerName = "Alexander Johannes van der Westhuizen",
            companyName = "International Infrastructure Compliance and Engineering Services GmbH"
        )

        assertEquals(
            "Senior Environmental Compliance and Quality Assurance Inspector",
            signature.role
        )
        assertEquals("Alexander Johannes van der Westhuizen", signature.signerName)
        assertEquals(
            "International Infrastructure Compliance and Engineering Services GmbH",
            signature.companyName
        )
    }
}
