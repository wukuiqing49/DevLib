package com.wkq.nativepdf.model

data class NativeReportData(
    val reportId: String,
    val title: String,
    val projectName: String,
    val location: String = "",
    val createdAtText: String = "",
    val generatedAtText: String = "",
    val company: NativeReportCompany = NativeReportCompany(),
    val summary: NativeReportSummary = NativeReportSummary(),
    val issues: List<NativeReportIssue> = emptyList(),
    val items: List<NativeReportItem> = emptyList(),
    val photos: List<NativeReportPhoto> = emptyList(),
    val signatures: List<NativeReportSignature> = emptyList(),
    val coverImagePath: String? = null,
    val metadata: List<Pair<String, String>> = emptyList(),
    val labels: NativeReportLabels = NativeReportLabels()
)

data class NativeReportLabels(
    val siteReport: String = "SITE REPORT",
    val siteInspectionReport: String = "SITE INSPECTION REPORT",
    val acceptanceRectification: String = "ACCEPTANCE & RECTIFICATION",
    val premiumReport: String = "PREMIUM REPORT",
    val generatedAt: String = "Generated",
    val totalItems: String = "Items",
    val issues: String = "Issues",
    val pending: String = "Pending",
    val signatures: String = "Signatures",
    val progress: String = "Progress",
    val project: String = "Project",
    val location: String = "Location",
    val createdAt: String = "Created",
    val conclusion: String = "Conclusion",
    val pendingConfirmation: String = "Pending",
    val reportSummary: String = "Report Summary",
    val projectSummary: String = "Project Summary",
    val clientDecisionSummary: String = "Client Decision Summary",
    val issueSummary: String = "Issue Summary",
    val rectificationFocus: String = "Rectification & Acceptance Items",
    val riskFocus: String = "Risks & Attention Items",
    val noIssues: String = "No issues require correction in this report.",
    val issue: String = "Issue",
    val recommendation: String = "Recommendation",
    val ownerDue: String = "Owner / Due",
    val acceptanceDetails: String = "Acceptance Details",
    val detailAppendix: String = "Details Appendix",
    val inspectionDetails: String = "Inspection Details",
    val photoEvidence: String = "Photo Evidence",
    val noPhotos: String = "No photo evidence.",
    val signOff: String = "Sign-off",
    val inspector: String = "Inspector",
    val notSet: String = "Not set",
    val deliveryConclusion: String = "Delivery Conclusion",
    val reportId: String = "Report ID",
    val projectName: String = "Project Name",
    val siteLocation: String = "Site Location",
    val reportInfo: String = "Report Info",
    val signStatementTitle: String = "Sign-off Statement",
    val signStatement: String = "I confirm this report records the inspection notes, issue descriptions, photo evidence and sign-off information for this field handover.",
    val sitePhoto: String = "Site Photo",
    val beforeCorrection: String = "Before correction",
    val afterCorrection: String = "After correction",
    val pageNumberFormat: String = "Page %1\$d"
)

data class NativeReportCompany(
    val name: String = "",
    val logoPath: String = "",
    val address: String = "",
    val phone: String = "",
    val inspectorName: String = ""
)

data class NativeReportSummary(
    val progressText: String = "",
    val totalCount: Int = 0,
    val passedCount: Int = 0,
    val issueCount: Int = 0,
    val pendingCount: Int = 0,
    val photoCount: Int = 0,
    val conclusionText: String = "",
    val conclusionStatus: NativeReportStatus = NativeReportStatus.WARNING
)

data class NativeReportIssue(
    val itemTitle: String,
    val issueText: String = "",
    val suggestionText: String = "",
    val ownerText: String = "",
    val status: NativeReportStatus = NativeReportStatus.WARNING
)

data class NativeReportItem(
    val group: String = "",
    val title: String,
    val statusText: String,
    val status: NativeReportStatus = NativeReportStatus.WARNING,
    val photoCountText: String = "",
    val recordText: String = ""
)

data class NativeReportPhoto(
    val filePath: String,
    val itemTitle: String = "",
    val description: String = "",
    val timeText: String = "",
    val locationText: String = "",
    val evidenceTypeText: String = "",
    val evidenceType: NativeReportEvidenceType = NativeReportEvidenceType.GENERAL
)

data class NativeReportSignature(
    val role: String,
    val signerName: String = "",
    val companyName: String = "",
    val signedAtText: String = "",
    val locationText: String = "",
    val avatarPath: String = "",
    val signatureImagePath: String = ""
)

enum class NativeReportStatus {
    SUCCESS,
    DANGER,
    WARNING,
    INFO,
    MUTED
}

enum class NativeReportEvidenceType {
    GENERAL,
    BEFORE,
    AFTER
}
