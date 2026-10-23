package com.wkq.photo.idlibrary

import com.wkq.photo.idlibrary.db.CatalogReleaseEntity
import com.wkq.photo.idlibrary.db.CompositionConstraintEntity
import com.wkq.photo.idlibrary.db.DocumentProgramEntity
import com.wkq.photo.idlibrary.db.JurisdictionEntity
import com.wkq.photo.idlibrary.db.JurisdictionNameEntity
import com.wkq.photo.idlibrary.db.LocalePackEntity
import com.wkq.photo.idlibrary.db.SourceEvidenceAssessmentEntity
import com.wkq.photo.idlibrary.db.SourceEvidenceEntity
import org.junit.Assert.assertThrows
import org.junit.Test

class CatalogPackageValidatorTest {
    @Test
    fun `接受 release 一致且语言标签有效的规则包`() {
        CatalogPackageValidator.validate(validPackage())
    }

    @Test
    fun `接受官方直接证据和多单位构图约束`() {
        val source = SourceEvidenceEntity(
            releaseId = RELEASE_ID,
            evidenceId = "official-source",
            authorityCode = "ica-sg",
            sourceLanguage = "en",
            originalTitle = "Official photo requirements",
            url = "https://www.ica.gov.sg/photo-requirements",
            retrievedAt = "2026-08-06T00:00:00Z",
            verifiedAt = "2026-08-06T00:00:00Z",
            contentSha256 = "test-official-source-sha256",
        )
        val value = validPackage().copy(
            compositionConstraints = listOf(
                CompositionConstraintEntity(
                    releaseId = RELEASE_ID,
                    constraintId = "head-height-mm",
                    variantId = "passport-print",
                    measurement = "head_height",
                    fromLandmark = "crown",
                    toLandmark = "chin",
                    referenceFrame = "full_image",
                    subjectScope = "adult",
                    minValue = "32",
                    maxValue = "36",
                    unit = "mm",
                    constraintLevel = "hard",
                ),
                CompositionConstraintEntity(
                    releaseId = RELEASE_ID,
                    constraintId = "eye-line-px",
                    variantId = "passport-digital",
                    measurement = "eye_line_from_top",
                    referenceFrame = "full_image",
                    subjectScope = "all",
                    targetValue = "260",
                    unit = "px",
                    constraintLevel = "hard",
                ),
                CompositionConstraintEntity(
                    releaseId = RELEASE_ID,
                    constraintId = "face-width-ratio",
                    variantId = "passport-digital",
                    measurement = "face_width",
                    fromLandmark = "left_face_edge",
                    toLandmark = "right_face_edge",
                    referenceFrame = "image_width",
                    subjectScope = "all",
                    minValue = "0.45",
                    maxValue = "0.55",
                    unit = "ratio",
                    constraintLevel = "hard",
                ),
            ),
            sourceEvidence = listOf(source),
            sourceEvidenceAssessments = listOf(
                SourceEvidenceAssessmentEntity(
                    releaseId = RELEASE_ID,
                    evidenceId = source.evidenceId,
                    sourceTier = "official_primary",
                    verificationStatus = "verified",
                    retrievalMethod = "direct",
                    httpStatus = 200,
                    checkedAt = "2026-08-06T00:00:00Z",
                ),
            ),
        )

        CatalogPackageValidator.validate(value)
    }

    @Test
    fun `拒绝跨 release 数据`() {
        val value = validPackage().copy(
            jurisdictionNames = listOf(
                JurisdictionNameEntity(
                    releaseId = "other-release",
                    jurisdictionCode = "SG",
                    localeTag = "en",
                    name = "Singapore",
                    translationStatus = "human_verified",
                ),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            CatalogPackageValidator.validate(value)
        }
    }

    @Test
    fun `拒绝 Android 资源目录式语言标签`() {
        val value = validPackage().copy(
            jurisdictionNames = listOf(
                JurisdictionNameEntity(
                    releaseId = RELEASE_ID,
                    jurisdictionCode = "SG",
                    localeTag = "zh_rCN",
                    name = "新加坡",
                    translationStatus = "human_verified",
                ),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            CatalogPackageValidator.validate(value)
        }
    }

    @Test
    fun `rejects composition constraints with an inverted range`() {
        val value = validPackage().copy(
            compositionConstraints = listOf(
                CompositionConstraintEntity(
                    releaseId = RELEASE_ID,
                    constraintId = "head-height",
                    variantId = "passport-print",
                    measurement = "head_height",
                    fromLandmark = "crown",
                    toLandmark = "chin",
                    referenceFrame = "full_image",
                    subjectScope = "adult",
                    minValue = "36",
                    maxValue = "32",
                    unit = "mm",
                    constraintLevel = "hard",
                ),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            CatalogPackageValidator.validate(value)
        }
    }

    @Test
    fun `rejects proxy evidence marked as verified`() {
        val source = SourceEvidenceEntity(
            releaseId = RELEASE_ID,
            evidenceId = "official-source",
            authorityCode = "authority",
            sourceLanguage = "en",
            originalTitle = "Official requirements",
            url = "https://example.gov/requirements",
            retrievedAt = "2026-08-06T00:00:00Z",
            verifiedAt = "2026-08-06T00:00:00Z",
            contentSha256 = "sha256",
        )
        val value = validPackage().copy(
            sourceEvidence = listOf(source),
            sourceEvidenceAssessments = listOf(
                SourceEvidenceAssessmentEntity(
                    releaseId = RELEASE_ID,
                    evidenceId = source.evidenceId,
                    sourceTier = "official_primary",
                    verificationStatus = "verified",
                    retrievalMethod = "proxy",
                    httpStatus = 200,
                    checkedAt = "2026-08-06T00:00:00Z",
                ),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            CatalogPackageValidator.validate(value)
        }
    }

    @Test
    fun `rejects document programs with an unknown authority`() {
        val value = validPackage().copy(
            documentPrograms = listOf(
                DocumentProgramEntity(
                    releaseId = RELEASE_ID,
                    programId = "sg-passport",
                    jurisdictionCode = "SG",
                    documentType = "passport",
                    programCode = "ordinary_passport",
                    issuingAuthorityCode = "missing-authority",
                    status = "active",
                ),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            CatalogPackageValidator.validate(value)
        }
    }

    private fun validPackage() = CatalogPackage(
        release = CatalogReleaseEntity(
            releaseId = RELEASE_ID,
            schemaVersion = 1,
            dataVersion = 1,
            publishedAt = "2026-08-06T00:00:00Z",
            importedAt = "2026-08-06T00:00:00Z",
            status = "verified",
            contentSha256 = "test-sha256",
            signatureKeyId = "test-key",
        ),
        localePacks = listOf(
            LocalePackEntity(
                releaseId = RELEASE_ID,
                localeTag = "en",
                packVersion = 1,
                status = "human_verified",
                publishedAt = "2026-08-06T00:00:00Z",
                contentSha256 = "test-locale-sha256",
                signatureKeyId = "test-key",
            ),
        ),
        jurisdictions = listOf(
            JurisdictionEntity(
                releaseId = RELEASE_ID,
                code = "SG",
                kind = "country_or_area",
            ),
        ),
        jurisdictionNames = listOf(
            JurisdictionNameEntity(
                releaseId = RELEASE_ID,
                jurisdictionCode = "SG",
                localeTag = "en",
                name = "Singapore",
                translationStatus = "human_verified",
            ),
        ),
    )

    private companion object {
        const val RELEASE_ID = "catalog-2026-08-06"
    }
}
