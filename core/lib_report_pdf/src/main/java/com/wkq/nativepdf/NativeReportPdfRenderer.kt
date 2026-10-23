package com.wkq.nativepdf

import android.content.Context
import android.graphics.pdf.PdfDocument
import com.wkq.nativepdf.model.NativeReportData
import com.wkq.nativepdf.model.NativeReportSectionType
import com.wkq.nativepdf.model.NativeReportTemplate
import com.wkq.nativepdf.render.NativePdfPageManager
import com.wkq.nativepdf.section.CoverSectionRenderer
import com.wkq.nativepdf.section.InspectionDetailsSectionRenderer
import com.wkq.nativepdf.section.IssueSummarySectionRenderer
import com.wkq.nativepdf.section.NativeSectionRenderer
import com.wkq.nativepdf.section.PhotoEvidenceSectionRenderer
import com.wkq.nativepdf.section.SignatureSectionRenderer
import com.wkq.nativepdf.section.SummarySectionRenderer
import java.io.File
import com.wkq.nativepdf.render.withPdfImageScale

class NativeReportPdfRenderer(
    @Suppress("unused") private val context: Context
) {
    private val renderers: Map<NativeReportSectionType, NativeSectionRenderer> = mapOf(
        NativeReportSectionType.COVER to CoverSectionRenderer(),
        NativeReportSectionType.SUMMARY to SummarySectionRenderer(),
        NativeReportSectionType.ISSUE_SUMMARY to IssueSummarySectionRenderer(),
        NativeReportSectionType.INSPECTION_DETAILS to InspectionDetailsSectionRenderer(),
        NativeReportSectionType.PHOTO_EVIDENCE to PhotoEvidenceSectionRenderer(),
        NativeReportSectionType.SIGNATURE to SignatureSectionRenderer()
    )

    fun renderToFile(
        data: NativeReportData,
        template: NativeReportTemplate = NativeReportTemplate.engineering(),
        outputFile: File
    ): File = renderToFile(data, template, outputFile) {}

    fun renderToFile(
        data: NativeReportData,
        template: NativeReportTemplate,
        outputFile: File,
        checkActive: () -> Unit
    ): File = withPdfImageScale(template.imageScale) {
        checkActive()
        val pdfDocument = PdfDocument()
        val pageManager = try {
            NativePdfPageManager(
                pdfDocument = pdfDocument,
                data = data,
                template = template,
                checkActive = checkActive
            )
        } catch (error: Throwable) {
            pdfDocument.close()
            throw error
        }
        try {
            val hasExplicitCover = template.showCoverImage &&
                !data.coverImagePath.isNullOrBlank() &&
                File(data.coverImagePath).isFile
            template.sections
                .filterNot { sectionType ->
                    sectionType == NativeReportSectionType.COVER && !hasExplicitCover
                }
                .forEach { sectionType ->
                    checkActive()
                    renderers[sectionType]?.render(pageManager, data)
                }
            pageManager.finish()
            return@withPdfImageScale writePdfOutput(outputFile, checkActive, pdfDocument::writeTo)
        } finally {
            try { pageManager.discardActivePage() } finally { pdfDocument.close() }
        }
    }
}
