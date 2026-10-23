package com.wkq.nativepdf.model

data class NativeReportTemplate(
    val id: String,
    val name: String,
    val theme: NativePdfTheme = NativePdfTheme.engineering(),
    val sections: List<NativeReportSectionType> = NativeReportSectionType.defaultOrder(),
    val source: NativeReportTemplateSource = NativeReportTemplateSource.BUILT_IN,
    val headerStyle: NativeReportHeaderStyle = NativeReportHeaderStyle.ENGINEERING,
    val footerStyle: NativeReportFooterStyle = NativeReportFooterStyle.STANDARD,
    val summaryStyle: NativeReportSummaryStyle = NativeReportSummaryStyle.METRIC_CARDS,
    val detailLayout: NativeReportDetailLayout = NativeReportDetailLayout.CARD,
    val photoLayout: NativeReportPhotoLayout = NativeReportPhotoLayout.GRID,
    val signatureLayout: NativeReportSignatureLayout = NativeReportSignatureLayout.CARDS,
    val headerText: String = "",
    val footerText: String = "",
    val inspectionNote: String = "",
    val showCoverImage: Boolean = true,
    val showCompanyLogo: Boolean = true,
    val showHeader: Boolean = true,
    val showFooter: Boolean = true,
    val showWatermark: Boolean = true,
    val watermarkText: String = "",
    val imageScale: Float = 3f
) {
    companion object {
        fun simple(): NativeReportTemplate = NativeReportTemplate(
            id = "native_simple",
            name = "Native Simple Report",
            theme = NativePdfTheme.client(),
            sections = simpleSectionOrder(),
            headerStyle = NativeReportHeaderStyle.MINIMAL,
            footerStyle = NativeReportFooterStyle.MINIMAL,
            summaryStyle = NativeReportSummaryStyle.COMPACT,
            detailLayout = NativeReportDetailLayout.COMPACT,
            photoLayout = NativeReportPhotoLayout.GRID,
            signatureLayout = NativeReportSignatureLayout.INLINE,
            showCoverImage = false,
            showCompanyLogo = false,
            showWatermark = false
        )

        internal fun simpleSectionOrder(): List<NativeReportSectionType> = listOf(
            NativeReportSectionType.COVER,
            NativeReportSectionType.SUMMARY,
            NativeReportSectionType.ISSUE_SUMMARY,
            NativeReportSectionType.INSPECTION_DETAILS,
            NativeReportSectionType.PHOTO_EVIDENCE,
            NativeReportSectionType.SIGNATURE
        )

        fun engineering(): NativeReportTemplate = NativeReportTemplate(
            id = "native_engineering",
            name = "Native Engineering Report",
            theme = NativePdfTheme.engineering(),
            sections = listOf(
                NativeReportSectionType.COVER,
                NativeReportSectionType.SUMMARY,
                NativeReportSectionType.ISSUE_SUMMARY,
                NativeReportSectionType.INSPECTION_DETAILS,
                NativeReportSectionType.PHOTO_EVIDENCE,
                NativeReportSectionType.SIGNATURE
            ),
            headerStyle = NativeReportHeaderStyle.ENGINEERING,
            footerStyle = NativeReportFooterStyle.STANDARD,
            summaryStyle = NativeReportSummaryStyle.METRIC_CARDS,
            detailLayout = NativeReportDetailLayout.CARD,
            photoLayout = NativeReportPhotoLayout.GRID,
            signatureLayout = NativeReportSignatureLayout.CARDS
        )

        fun acceptance(): NativeReportTemplate = NativeReportTemplate(
            id = "native_acceptance",
            name = "Native Acceptance Report",
            theme = NativePdfTheme.acceptance(),
            sections = listOf(
                NativeReportSectionType.COVER,
                NativeReportSectionType.SUMMARY,
                NativeReportSectionType.ISSUE_SUMMARY,
                NativeReportSectionType.PHOTO_EVIDENCE,
                NativeReportSectionType.SIGNATURE,
                NativeReportSectionType.INSPECTION_DETAILS
            ),
            headerStyle = NativeReportHeaderStyle.ACCEPTANCE,
            footerStyle = NativeReportFooterStyle.STANDARD,
            summaryStyle = NativeReportSummaryStyle.DECISION,
            detailLayout = NativeReportDetailLayout.CARD,
            photoLayout = NativeReportPhotoLayout.BEFORE_AFTER,
            signatureLayout = NativeReportSignatureLayout.APPROVAL_BLOCK
        )

        fun clientSummary(): NativeReportTemplate = NativeReportTemplate(
            id = "native_client_summary",
            name = "Native Client Summary Report",
            theme = NativePdfTheme.client(),
            sections = listOf(
                NativeReportSectionType.COVER,
                NativeReportSectionType.SUMMARY,
                NativeReportSectionType.ISSUE_SUMMARY,
                NativeReportSectionType.PHOTO_EVIDENCE,
                NativeReportSectionType.SIGNATURE,
                NativeReportSectionType.INSPECTION_DETAILS
            ),
            headerStyle = NativeReportHeaderStyle.CLIENT_COVER,
            footerStyle = NativeReportFooterStyle.MINIMAL,
            summaryStyle = NativeReportSummaryStyle.EXECUTIVE,
            detailLayout = NativeReportDetailLayout.APPENDIX,
            photoLayout = NativeReportPhotoLayout.HIGHLIGHT,
            signatureLayout = NativeReportSignatureLayout.APPROVAL_BLOCK,
            showWatermark = false
        )
    }
}

enum class NativeReportTemplateSource {
    BUILT_IN,
    CUSTOM
}

enum class NativeReportHeaderStyle {
    MINIMAL,
    ENGINEERING,
    ACCEPTANCE,
    CLIENT_COVER,
    PREMIUM
}

enum class NativeReportFooterStyle {
    MINIMAL,
    STANDARD,
    BRANDED
}

enum class NativeReportSummaryStyle {
    COMPACT,
    METRIC_CARDS,
    DECISION,
    EXECUTIVE,
    PREMIUM
}

enum class NativeReportDetailLayout {
    COMPACT,
    CARD,
    APPENDIX
}

enum class NativeReportPhotoLayout {
    GRID,
    BEFORE_AFTER,
    HIGHLIGHT
}

enum class NativeReportSignatureLayout {
    INLINE,
    CARDS,
    APPROVAL_BLOCK
}

enum class NativeReportSectionType {
    COVER,
    SUMMARY,
    ISSUE_SUMMARY,
    INSPECTION_DETAILS,
    PHOTO_EVIDENCE,
    SIGNATURE;

    companion object {
        fun defaultOrder(): List<NativeReportSectionType> = listOf(
            COVER,
            SUMMARY,
            ISSUE_SUMMARY,
            INSPECTION_DETAILS,
            PHOTO_EVIDENCE,
            SIGNATURE
        )
    }
}
