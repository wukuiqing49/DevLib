package com.wkq.photo.idlibrary

import com.wkq.photo.idlibrary.db.AllowedBackgroundEntity
import com.wkq.photo.idlibrary.db.ApplicationChannelEntity
import com.wkq.photo.idlibrary.db.ApplicationChannelTextEntity
import com.wkq.photo.idlibrary.db.ArtifactConstraintEntity
import com.wkq.photo.idlibrary.db.BackgroundConstraintEntity
import com.wkq.photo.idlibrary.db.CatalogReleaseEntity
import com.wkq.photo.idlibrary.db.CompositionRuleEntity
import com.wkq.photo.idlibrary.db.CompositionConstraintEntity
import com.wkq.photo.idlibrary.db.CoverageStatusEntity
import com.wkq.photo.idlibrary.db.DocumentProgramEntity
import com.wkq.photo.idlibrary.db.DocumentProgramTextEntity
import com.wkq.photo.idlibrary.db.JurisdictionEntity
import com.wkq.photo.idlibrary.db.JurisdictionMembershipEntity
import com.wkq.photo.idlibrary.db.JurisdictionNameEntity
import com.wkq.photo.idlibrary.db.JurisdictionRelationEntity
import com.wkq.photo.idlibrary.db.IssuingAuthorityEntity
import com.wkq.photo.idlibrary.db.IssuingAuthorityTextEntity
import com.wkq.photo.idlibrary.db.LocalePackEntity
import com.wkq.photo.idlibrary.db.OutputFormatEntity
import com.wkq.photo.idlibrary.db.OutputEncodingEntity
import com.wkq.photo.idlibrary.db.OutputVariantEntity
import com.wkq.photo.idlibrary.db.OutputVariantTextEntity
import com.wkq.photo.idlibrary.db.PhotoRuleEntity
import com.wkq.photo.idlibrary.db.ProgramSourceLinkEntity
import com.wkq.photo.idlibrary.db.RequirementEntity
import com.wkq.photo.idlibrary.db.RequirementTextEntity
import com.wkq.photo.idlibrary.db.SourceEvidenceEntity
import com.wkq.photo.idlibrary.db.SourceEvidenceAssessmentEntity
import com.wkq.photo.idlibrary.db.SourceEvidenceTranslationEntity
import com.wkq.photo.idlibrary.db.SourceImagePolicyEntity
import com.wkq.photo.idlibrary.db.SubmissionArtifactEntity
import com.wkq.photo.idlibrary.db.TransformPolicyEntity
import com.wkq.photo.idlibrary.db.RuleScopeEntity
import com.wkq.photo.idlibrary.db.RuleSourceLinkEntity
import com.wkq.photo.idlibrary.db.CoverageSourceLinkEntity
import com.wkq.photo.idlibrary.db.VariantCapabilityEntity
import com.wkq.photo.idlibrary.db.VariantSourceLinkEntity
import com.wkq.photo.idlibrary.i18n.LocaleFallback
import java.util.Locale

data class CatalogPackage(
    val release: CatalogReleaseEntity,
    val localePacks: List<LocalePackEntity> = emptyList(),
    val jurisdictions: List<JurisdictionEntity>,
    val jurisdictionNames: List<JurisdictionNameEntity>,
    val jurisdictionMemberships: List<JurisdictionMembershipEntity> = emptyList(),
    val jurisdictionRelations: List<JurisdictionRelationEntity> = emptyList(),
    val coverageStatuses: List<CoverageStatusEntity> = emptyList(),
    val issuingAuthorities: List<IssuingAuthorityEntity> = emptyList(),
    val issuingAuthorityTexts: List<IssuingAuthorityTextEntity> = emptyList(),
    val documentPrograms: List<DocumentProgramEntity> = emptyList(),
    val documentProgramTexts: List<DocumentProgramTextEntity> = emptyList(),
    val applicationChannels: List<ApplicationChannelEntity> = emptyList(),
    val applicationChannelTexts: List<ApplicationChannelTextEntity> = emptyList(),
    val submissionArtifacts: List<SubmissionArtifactEntity> = emptyList(),
    val photoRules: List<PhotoRuleEntity> = emptyList(),
    val ruleScopes: List<RuleScopeEntity> = emptyList(),
    val outputVariants: List<OutputVariantEntity> = emptyList(),
    val outputVariantTexts: List<OutputVariantTextEntity> = emptyList(),
    val variantCapabilities: List<VariantCapabilityEntity> = emptyList(),
    val outputFormats: List<OutputFormatEntity> = emptyList(),
    val outputEncodings: List<OutputEncodingEntity> = emptyList(),
    val allowedBackgrounds: List<AllowedBackgroundEntity> = emptyList(),
    val compositionRules: List<CompositionRuleEntity> = emptyList(),
    val compositionConstraints: List<CompositionConstraintEntity> = emptyList(),
    val backgroundConstraints: List<BackgroundConstraintEntity> = emptyList(),
    val artifactConstraints: List<ArtifactConstraintEntity> = emptyList(),
    val sourceImagePolicies: List<SourceImagePolicyEntity> = emptyList(),
    val transformPolicies: List<TransformPolicyEntity> = emptyList(),
    val requirements: List<RequirementEntity> = emptyList(),
    val requirementTexts: List<RequirementTextEntity> = emptyList(),
    val sourceEvidence: List<SourceEvidenceEntity> = emptyList(),
    val sourceEvidenceTranslations: List<SourceEvidenceTranslationEntity> = emptyList(),
    val sourceEvidenceAssessments: List<SourceEvidenceAssessmentEntity> = emptyList(),
    val programSourceLinks: List<ProgramSourceLinkEntity> = emptyList(),
    val ruleSourceLinks: List<RuleSourceLinkEntity> = emptyList(),
    val coverageSourceLinks: List<CoverageSourceLinkEntity> = emptyList(),
    val sourceLinks: List<VariantSourceLinkEntity> = emptyList(),
)

object CatalogPackageValidator {
    fun validate(value: CatalogPackage) {
        val releaseId = value.release.releaseId
        require(releaseId.isNotBlank()) { "releaseId 不能为空" }
        require(value.release.schemaVersion > 0) { "schemaVersion 必须大于 0" }
        require(value.release.dataVersion > 0) { "dataVersion 必须大于 0" }
        require(value.jurisdictions.isNotEmpty()) { "证件库至少需要一个国家或地区" }

        val allRows = listOf(
            value.localePacks.map { it.releaseId },
            value.jurisdictions.map { it.releaseId },
            value.jurisdictionNames.map { it.releaseId },
            value.jurisdictionMemberships.map { it.releaseId },
            value.jurisdictionRelations.map { it.releaseId },
            value.coverageStatuses.map { it.releaseId },
            value.issuingAuthorities.map { it.releaseId },
            value.issuingAuthorityTexts.map { it.releaseId },
            value.documentPrograms.map { it.releaseId },
            value.documentProgramTexts.map { it.releaseId },
            value.applicationChannels.map { it.releaseId },
            value.applicationChannelTexts.map { it.releaseId },
            value.submissionArtifacts.map { it.releaseId },
            value.photoRules.map { it.releaseId },
            value.ruleScopes.map { it.releaseId },
            value.outputVariants.map { it.releaseId },
            value.outputVariantTexts.map { it.releaseId },
            value.variantCapabilities.map { it.releaseId },
            value.outputFormats.map { it.releaseId },
            value.outputEncodings.map { it.releaseId },
            value.allowedBackgrounds.map { it.releaseId },
            value.compositionRules.map { it.releaseId },
            value.compositionConstraints.map { it.releaseId },
            value.backgroundConstraints.map { it.releaseId },
            value.artifactConstraints.map { it.releaseId },
            value.sourceImagePolicies.map { it.releaseId },
            value.transformPolicies.map { it.releaseId },
            value.requirements.map { it.releaseId },
            value.requirementTexts.map { it.releaseId },
            value.sourceEvidence.map { it.releaseId },
            value.sourceEvidenceTranslations.map { it.releaseId },
            value.sourceEvidenceAssessments.map { it.releaseId },
            value.programSourceLinks.map { it.releaseId },
            value.ruleSourceLinks.map { it.releaseId },
            value.coverageSourceLinks.map { it.releaseId },
            value.sourceLinks.map { it.releaseId },
        ).flatten()
        require(allRows.all { it == releaseId }) { "规则包中存在跨 releaseId 数据" }

        val translatedLocaleTags = buildList {
            addAll(value.jurisdictionNames.map { it.localeTag })
            addAll(value.issuingAuthorityTexts.map { it.localeTag })
            addAll(value.documentProgramTexts.map { it.localeTag })
            addAll(value.applicationChannelTexts.map { it.localeTag })
            addAll(value.outputVariantTexts.map { it.localeTag })
            addAll(value.requirementTexts.map { it.localeTag })
            addAll(value.sourceEvidenceTranslations.map { it.localeTag })
        }
        val localeTags = value.localePacks.map { it.localeTag } +
            translatedLocaleTags + value.sourceEvidence.map { it.sourceLanguage }
        require(localeTags.all(LocaleFallback::isValidLanguageTag)) {
            "规则包语言必须使用有效的 BCP-47 标签"
        }
        val authorityCodes = value.issuingAuthorities.map { it.authorityCode }.toSet()
        require(value.documentPrograms.all { program ->
            program.issuingAuthorityCode == null || program.issuingAuthorityCode in authorityCodes
        }) { "document_program references an unknown issuing authority" }
        require(value.issuingAuthorities.all { authority ->
            authority.officialWebsite == null || authority.officialWebsite.startsWith("https://")
        }) { "Official authority websites must use HTTPS" }
        require(value.ruleScopes.all { scope ->
            (scope.minAgeMonths == null || scope.minAgeMonths >= 0) &&
                (scope.maxAgeMonths == null || scope.maxAgeMonths >= 0) &&
                (scope.minAgeMonths == null || scope.maxAgeMonths == null ||
                    scope.minAgeMonths <= scope.maxAgeMonths)
        }) { "Rule scope contains an invalid age range" }
        require(value.compositionConstraints.all(::isValidCompositionConstraint)) {
            "Composition constraint must contain a valid numeric target or range"
        }
        require(value.backgroundConstraints.all(::isValidBackgroundConstraint)) {
            "Background constraint is incomplete"
        }
        require(value.variantCapabilities.all { it.availabilityMode in AVAILABILITY_MODES }) {
            "Unknown variant availability mode"
        }
        require(value.transformPolicies.all { it.policy in TRANSFORM_POLICIES }) {
            "Unknown transform policy"
        }
        val evidenceById = value.sourceEvidence.associateBy { it.evidenceId }
        require(value.sourceEvidenceAssessments.all { assessment ->
            val source = evidenceById[assessment.evidenceId] ?: return@all false
            if (assessment.verificationStatus !in VERIFIED_STATUSES) return@all true
            assessment.sourceTier in OFFICIAL_SOURCE_TIERS &&
                assessment.retrievalMethod == "direct" &&
                assessment.httpStatus != null && assessment.httpStatus in 200..399 &&
                source.verifiedAt != null &&
                !source.contentSha256.isNullOrBlank()
        }) { "Verified evidence must be directly retrieved, official, dated, and hashed" }

        val declaredLocales = value.localePacks.map { it.localeTag.lowercase(Locale.ROOT) }.toSet()
        val translatedLocales = translatedLocaleTags.map { it.lowercase(Locale.ROOT) }.toSet()
        require(declaredLocales.containsAll(translatedLocales)) {
            "每种译文语言都必须声明对应的 locale_pack"
        }
    }
}
