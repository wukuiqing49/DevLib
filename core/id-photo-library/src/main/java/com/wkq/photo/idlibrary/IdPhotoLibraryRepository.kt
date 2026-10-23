package com.wkq.photo.idlibrary

import androidx.room.withTransaction
import com.wkq.photo.idlibrary.db.AllowedBackgroundEntity
import com.wkq.photo.idlibrary.db.ApplicationChannelEntity
import com.wkq.photo.idlibrary.db.ApplicationChannelTextEntity
import com.wkq.photo.idlibrary.db.ArtifactConstraintEntity
import com.wkq.photo.idlibrary.db.BackgroundConstraintEntity
import com.wkq.photo.idlibrary.db.CatalogReleaseEntity
import com.wkq.photo.idlibrary.db.CatalogStateEntity
import com.wkq.photo.idlibrary.db.CompositionRuleEntity
import com.wkq.photo.idlibrary.db.CompositionConstraintEntity
import com.wkq.photo.idlibrary.db.CoverageSourceLinkEntity
import com.wkq.photo.idlibrary.db.DocumentProgramEntity
import com.wkq.photo.idlibrary.db.DocumentProgramTextEntity
import com.wkq.photo.idlibrary.db.IdPhotoLibraryDatabase
import com.wkq.photo.idlibrary.db.IssuingAuthorityEntity
import com.wkq.photo.idlibrary.db.IssuingAuthorityTextEntity
import com.wkq.photo.idlibrary.db.JurisdictionEntity
import com.wkq.photo.idlibrary.db.JurisdictionNameEntity
import com.wkq.photo.idlibrary.db.JurisdictionRelationEntity
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
import com.wkq.photo.idlibrary.db.VariantCapabilityEntity
import com.wkq.photo.idlibrary.db.VariantSourceLinkEntity
import com.wkq.photo.idlibrary.i18n.LocaleFallback
import com.wkq.photo.idlibrary.i18n.LocalizedValue
import java.text.Collator
import java.util.Locale

data class LocalizedJurisdiction(
    val value: JurisdictionEntity,
    val displayName: String,
    val resolvedLocaleTag: String?,
    val translationStatus: String?,
)

data class LocalizedDocumentProgram(
    val value: DocumentProgramEntity,
    val displayName: String,
    val description: String?,
    val resolvedLocaleTag: String?,
    val translationStatus: String?,
)

data class LocalizedApplicationChannel(
    val value: ApplicationChannelEntity,
    val displayName: String,
    val instructions: String?,
    val resolvedLocaleTag: String?,
    val translationStatus: String?,
)

data class LocalizedIssuingAuthority(
    val value: IssuingAuthorityEntity,
    val displayName: String,
    val description: String?,
    val resolvedLocaleTag: String?,
    val translationStatus: String?,
)

data class LocalizedOutputVariant(
    val value: OutputVariantEntity,
    val displayName: String,
    val description: String?,
    val resolvedLocaleTag: String?,
    val translationStatus: String?,
)

data class LocalizedRequirement(
    val value: RequirementEntity,
    val text: String,
    val resolvedLocaleTag: String?,
    val translationStatus: String?,
)

data class LocalizedSourceEvidence(
    val value: SourceEvidenceEntity,
    val displayTitle: String,
    val summary: String?,
    val resolvedLocaleTag: String,
    val translationStatus: String?,
)

data class ProgramRuleSet(
    val releaseId: String,
    val rules: List<PhotoRuleEntity>,
    val variants: List<LocalizedOutputVariant>,
    val compositions: List<CompositionRuleEntity>,
    val formats: List<OutputFormatEntity>,
    val backgrounds: List<AllowedBackgroundEntity>,
    val sourceImagePolicies: List<SourceImagePolicyEntity>,
    val requirements: List<LocalizedRequirement>,
    val sourceLinks: List<VariantSourceLinkEntity>,
    val sourceEvidence: List<LocalizedSourceEvidence>,
    val applicationChannels: List<ApplicationChannelEntity> = emptyList(),
    val ruleScopes: List<RuleScopeEntity> = emptyList(),
    val submissionArtifacts: List<SubmissionArtifactEntity> = emptyList(),
    val capabilities: List<VariantCapabilityEntity> = emptyList(),
    val outputEncodings: List<OutputEncodingEntity> = emptyList(),
    val compositionConstraints: List<CompositionConstraintEntity> = emptyList(),
    val backgroundConstraints: List<BackgroundConstraintEntity> = emptyList(),
    val artifactConstraints: List<ArtifactConstraintEntity> = emptyList(),
    val transformPolicies: List<TransformPolicyEntity> = emptyList(),
    val programSourceLinks: List<ProgramSourceLinkEntity> = emptyList(),
    val ruleSourceLinks: List<RuleSourceLinkEntity> = emptyList(),
    val sourceAssessments: List<SourceEvidenceAssessmentEntity> = emptyList(),
)

class IdPhotoLibraryRepository(
    private val database: IdPhotoLibraryDatabase,
) {
    private val dao = database.catalogDao()

    suspend fun releases(): List<CatalogReleaseEntity> = dao.releases()

    suspend fun activeReleaseId(): String? = dao.activeReleaseId()

    suspend fun activeRelease(): CatalogReleaseEntity? = dao.activeRelease()

    suspend fun activeLocalePacks(): List<LocalePackEntity> =
        dao.activeReleaseId()?.let { dao.localePacks(it) }.orEmpty()

    suspend fun jurisdictionMemberCodes(groupCode: String): List<String> {
        val releaseId = dao.activeReleaseId() ?: return emptyList()
        return dao.jurisdictionMembers(releaseId, groupCode).map { it.memberCode }
    }

    suspend fun jurisdictionChildCodes(parentCode: String): List<String> {
        val releaseId = dao.activeReleaseId() ?: return emptyList()
        return dao.jurisdictionChildren(releaseId, parentCode).map(JurisdictionRelationEntity::childCode)
    }

    suspend fun coverageStatuses(jurisdictionCode: String) =
        dao.activeReleaseId()?.let { dao.coverageStatuses(it, jurisdictionCode) }.orEmpty()

    suspend fun coverageSourceLinks(
        jurisdictionCode: String,
        documentType: String,
    ): List<CoverageSourceLinkEntity> = dao.activeReleaseId()?.let {
        dao.coverageSourceLinks(it, jurisdictionCode, documentType)
    }.orEmpty()

    suspend fun listIssuingAuthorities(locale: Locale): List<LocalizedIssuingAuthority> {
        val releaseId = dao.activeReleaseId() ?: return emptyList()
        val authorities = dao.issuingAuthorities(releaseId)
        if (authorities.isEmpty()) return emptyList()
        val texts = dao.issuingAuthorityTexts(
            releaseId,
            authorities.map { it.authorityId },
            LocaleFallback.candidateTags(locale),
        ).groupBy { it.authorityId }
        return authorities.map { authority ->
            localizeAuthority(authority, texts[authority.authorityId].orEmpty(), locale)
        }
    }

    suspend fun listJurisdictions(locale: Locale): List<LocalizedJurisdiction> {
        val releaseId = dao.activeReleaseId() ?: return emptyList()
        val locales = LocaleFallback.candidateTags(locale)
        val names = dao.jurisdictionNames(releaseId, locales).groupBy { it.jurisdictionCode }
        val localized = dao.jurisdictions(releaseId).map { jurisdiction ->
            val localized = LocaleFallback.resolve(
                values = names[jurisdiction.code].orEmpty().map {
                    LocalizedValue(it.name, it.localeTag, it.translationStatus)
                },
                locale = locale,
                stableFallback = jurisdiction.code,
            )
            LocalizedJurisdiction(
                value = jurisdiction,
                displayName = localized.value,
                resolvedLocaleTag = localized.localeTag,
                translationStatus = localized.translationStatus,
            )
        }
        val collator = Collator.getInstance(locale)
        return localized.sortedWith { left, right -> collator.compare(left.displayName, right.displayName) }
    }

    suspend fun listDocumentPrograms(
        jurisdictionCode: String,
        documentType: String? = null,
        locale: Locale,
    ): List<LocalizedDocumentProgram> {
        val releaseId = dao.activeReleaseId() ?: return emptyList()
        val programs = dao.documentPrograms(releaseId, jurisdictionCode, documentType)
        if (programs.isEmpty()) return emptyList()
        val texts = dao.documentProgramTexts(
            releaseId,
            programs.map { it.programId },
            LocaleFallback.candidateTags(locale),
        ).groupBy { it.programId }
        return programs.map { program -> localizeProgram(program, texts[program.programId].orEmpty(), locale) }
    }

    suspend fun listApplicationChannels(
        programId: String,
        locale: Locale,
    ): List<LocalizedApplicationChannel> {
        val releaseId = dao.activeReleaseId() ?: return emptyList()
        val channels = dao.applicationChannels(releaseId, programId)
        if (channels.isEmpty()) return emptyList()
        val texts = dao.applicationChannelTexts(
            releaseId,
            channels.map { it.channelId },
            LocaleFallback.candidateTags(locale),
        ).groupBy { it.channelId }
        return channels.map { channel -> localizeChannel(channel, texts[channel.channelId].orEmpty(), locale) }
    }

    suspend fun getProgramRuleSet(programId: String, locale: Locale): ProgramRuleSet? {
        val releaseId = dao.activeReleaseId() ?: return null
        val rules = dao.photoRules(releaseId, programId)
        if (rules.isEmpty()) return emptyProgramRuleSet(releaseId)
        val ruleIds = rules.map { it.ruleId }
        val variants = dao.outputVariants(releaseId, ruleIds)
        if (variants.isEmpty()) return emptyProgramRuleSet(releaseId, rules)
        val variantIds = variants.map { it.variantId }
        val localizedVariants = localizeVariants(releaseId, variants, locale)
        val requirements = dao.requirements(releaseId, variantIds)
        val localizedRequirements = localizeRequirements(releaseId, requirements, locale)
        val sourceLinks = dao.sourceLinks(releaseId, variantIds)
        val programSourceLinks = dao.programSourceLinks(releaseId, programId)
        val ruleSourceLinks = dao.ruleSourceLinks(releaseId, ruleIds)
        val evidenceIds = buildSet {
            addAll(sourceLinks.map { it.evidenceId })
            addAll(programSourceLinks.map { it.evidenceId })
            addAll(ruleSourceLinks.map { it.evidenceId })
        }.toList()
        val localizedEvidence = localizeEvidence(releaseId, evidenceIds, locale)
        val channelIds = rules.mapNotNull { it.channelId }.distinct()
        return ProgramRuleSet(
            releaseId = releaseId,
            rules = rules,
            variants = localizedVariants,
            compositions = dao.compositionRules(releaseId, variantIds),
            formats = dao.outputFormats(releaseId, variantIds),
            backgrounds = dao.allowedBackgrounds(releaseId, variantIds),
            sourceImagePolicies = dao.sourceImagePolicies(releaseId, ruleIds),
            requirements = localizedRequirements,
            sourceLinks = sourceLinks,
            sourceEvidence = localizedEvidence,
            applicationChannels = dao.applicationChannels(releaseId, programId),
            ruleScopes = dao.ruleScopes(releaseId, ruleIds),
            submissionArtifacts = if (channelIds.isEmpty()) emptyList()
            else dao.submissionArtifacts(releaseId, channelIds),
            capabilities = dao.variantCapabilities(releaseId, variantIds),
            outputEncodings = dao.outputEncodings(releaseId, variantIds),
            compositionConstraints = dao.compositionConstraints(releaseId, variantIds),
            backgroundConstraints = dao.backgroundConstraints(releaseId, variantIds),
            artifactConstraints = dao.artifactConstraints(releaseId, variantIds),
            transformPolicies = dao.transformPolicies(releaseId, ruleIds),
            programSourceLinks = programSourceLinks,
            ruleSourceLinks = ruleSourceLinks,
            sourceAssessments = if (evidenceIds.isEmpty()) emptyList()
            else dao.sourceEvidenceAssessments(releaseId, evidenceIds),
        )
    }

    /** 调用方必须先完成 HTTPS、哈希、数字签名和 schema 版本校验。 */
    suspend fun installVerifiedPackage(value: CatalogPackage, activate: Boolean = true) {
        CatalogPackageValidator.validate(value)
        database.withTransaction {
            dao.insertRelease(value.release)
            dao.insertLocalePacks(value.localePacks)
            dao.insertJurisdictions(value.jurisdictions)
            dao.insertJurisdictionNames(value.jurisdictionNames)
            dao.insertJurisdictionMemberships(value.jurisdictionMemberships)
            dao.insertJurisdictionRelations(value.jurisdictionRelations)
            dao.insertCoverageStatuses(value.coverageStatuses)
            dao.insertIssuingAuthorities(value.issuingAuthorities)
            dao.insertIssuingAuthorityTexts(value.issuingAuthorityTexts)
            dao.insertDocumentPrograms(value.documentPrograms)
            dao.insertDocumentProgramTexts(value.documentProgramTexts)
            dao.insertApplicationChannels(value.applicationChannels)
            dao.insertApplicationChannelTexts(value.applicationChannelTexts)
            dao.insertSubmissionArtifacts(value.submissionArtifacts)
            dao.insertPhotoRules(value.photoRules)
            dao.insertRuleScopes(value.ruleScopes)
            dao.insertOutputVariants(value.outputVariants)
            dao.insertOutputVariantTexts(value.outputVariantTexts)
            dao.insertVariantCapabilities(value.variantCapabilities)
            dao.insertOutputFormats(value.outputFormats)
            dao.insertOutputEncodings(value.outputEncodings)
            dao.insertAllowedBackgrounds(value.allowedBackgrounds)
            dao.insertCompositionRules(value.compositionRules)
            dao.insertCompositionConstraints(value.compositionConstraints)
            dao.insertBackgroundConstraints(value.backgroundConstraints)
            dao.insertArtifactConstraints(value.artifactConstraints)
            dao.insertSourceImagePolicies(value.sourceImagePolicies)
            dao.insertTransformPolicies(value.transformPolicies)
            dao.insertRequirements(value.requirements)
            dao.insertRequirementTexts(value.requirementTexts)
            dao.insertSourceEvidence(value.sourceEvidence)
            dao.insertSourceEvidenceTranslations(value.sourceEvidenceTranslations)
            dao.insertSourceEvidenceAssessments(value.sourceEvidenceAssessments)
            dao.insertProgramSourceLinks(value.programSourceLinks)
            dao.insertRuleSourceLinks(value.ruleSourceLinks)
            dao.insertCoverageSourceLinks(value.coverageSourceLinks)
            dao.insertSourceLinks(value.sourceLinks)
            if (activate) dao.setCatalogState(CatalogStateEntity(activeReleaseId = value.release.releaseId))
        }
    }

    suspend fun activateRelease(releaseId: String) {
        require(dao.releaseExists(releaseId)) { "待启用的证件库版本不存在: $releaseId" }
        dao.setCatalogState(CatalogStateEntity(activeReleaseId = releaseId))
    }

    suspend fun deleteInactiveRelease(releaseId: String): Boolean {
        require(dao.activeReleaseId() != releaseId) { "不能删除当前启用的证件库版本" }
        return dao.deleteRelease(releaseId) > 0
    }

    private suspend fun localizeVariants(
        releaseId: String,
        variants: List<OutputVariantEntity>,
        locale: Locale,
    ): List<LocalizedOutputVariant> {
        val texts = dao.outputVariantTexts(
            releaseId,
            variants.map { it.variantId },
            LocaleFallback.candidateTags(locale),
        ).groupBy { it.variantId }
        return variants.map { variant ->
            val candidates = texts[variant.variantId].orEmpty()
            val localized = LocaleFallback.resolve(
                candidates.map { LocalizedValue(it.name, it.localeTag, it.translationStatus) },
                locale,
                variant.variantId,
            )
            LocalizedOutputVariant(
                value = variant,
                displayName = localized.value,
                description = candidates.firstOrNull {
                    it.localeTag.equals(localized.localeTag, ignoreCase = true)
                }?.description,
                resolvedLocaleTag = localized.localeTag,
                translationStatus = localized.translationStatus,
            )
        }
    }

    private suspend fun localizeRequirements(
        releaseId: String,
        requirements: List<RequirementEntity>,
        locale: Locale,
    ): List<LocalizedRequirement> {
        if (requirements.isEmpty()) return emptyList()
        val texts = dao.requirementTexts(
            releaseId,
            requirements.map { it.requirementId },
            LocaleFallback.candidateTags(locale),
        ).groupBy { it.requirementId }
        return requirements.map { requirement ->
            val localized = LocaleFallback.resolve(
                texts[requirement.requirementId].orEmpty().map {
                    LocalizedValue(it.text, it.localeTag, it.translationStatus)
                },
                locale,
                requirement.requirementId,
            )
            LocalizedRequirement(
                value = requirement,
                text = localized.value,
                resolvedLocaleTag = localized.localeTag,
                translationStatus = localized.translationStatus,
            )
        }
    }

    private suspend fun localizeEvidence(
        releaseId: String,
        evidenceIds: List<String>,
        locale: Locale,
    ): List<LocalizedSourceEvidence> {
        if (evidenceIds.isEmpty()) return emptyList()
        val distinctEvidenceIds = evidenceIds.distinct()
        val evidence = dao.sourceEvidence(releaseId, distinctEvidenceIds)
        val translations = dao.sourceEvidenceTranslations(
            releaseId,
            distinctEvidenceIds,
            LocaleFallback.candidateTags(locale),
        ).groupBy { it.evidenceId }
        return evidence.map { source -> localizeEvidence(source, translations[source.evidenceId].orEmpty(), locale) }
    }

    private fun localizeAuthority(
        authority: IssuingAuthorityEntity,
        texts: List<IssuingAuthorityTextEntity>,
        locale: Locale,
    ): LocalizedIssuingAuthority {
        val localized = LocaleFallback.resolve(
            texts.map { LocalizedValue(it.name, it.localeTag, it.translationStatus) },
            locale,
            authority.authorityCode,
        )
        return LocalizedIssuingAuthority(
            value = authority,
            displayName = localized.value,
            description = texts.firstOrNull {
                it.localeTag.equals(localized.localeTag, ignoreCase = true)
            }?.description,
            resolvedLocaleTag = localized.localeTag,
            translationStatus = localized.translationStatus,
        )
    }

    private fun localizeProgram(
        program: DocumentProgramEntity,
        texts: List<DocumentProgramTextEntity>,
        locale: Locale,
    ): LocalizedDocumentProgram {
        val localized = LocaleFallback.resolve(
            texts.map { LocalizedValue(it.name, it.localeTag, it.translationStatus) },
            locale,
            program.programCode,
        )
        return LocalizedDocumentProgram(
            value = program,
            displayName = localized.value,
            description = texts.firstOrNull {
                it.localeTag.equals(localized.localeTag, ignoreCase = true)
            }?.description,
            resolvedLocaleTag = localized.localeTag,
            translationStatus = localized.translationStatus,
        )
    }

    private fun localizeChannel(
        channel: ApplicationChannelEntity,
        texts: List<ApplicationChannelTextEntity>,
        locale: Locale,
    ): LocalizedApplicationChannel {
        val localized = LocaleFallback.resolve(
            texts.map { LocalizedValue(it.name, it.localeTag, it.translationStatus) },
            locale,
            channel.channelType,
        )
        return LocalizedApplicationChannel(
            value = channel,
            displayName = localized.value,
            instructions = texts.firstOrNull {
                it.localeTag.equals(localized.localeTag, ignoreCase = true)
            }?.instructions,
            resolvedLocaleTag = localized.localeTag,
            translationStatus = localized.translationStatus,
        )
    }

    private fun localizeEvidence(
        source: SourceEvidenceEntity,
        translations: List<SourceEvidenceTranslationEntity>,
        locale: Locale,
    ): LocalizedSourceEvidence {
        val localized = LocaleFallback.resolve(
            translations.map {
                LocalizedValue(
                    value = it.translatedTitle ?: source.originalTitle,
                    localeTag = it.localeTag,
                    translationStatus = it.translationStatus,
                )
            },
            locale,
            source.originalTitle,
        )
        val translation = translations.firstOrNull {
            it.localeTag.equals(localized.localeTag, ignoreCase = true)
        }
        return LocalizedSourceEvidence(
            value = source,
            displayTitle = localized.value,
            summary = translation?.translatedSummary ?: source.originalQuote,
            resolvedLocaleTag = localized.localeTag ?: source.sourceLanguage,
            translationStatus = localized.translationStatus,
        )
    }
}

private fun emptyProgramRuleSet(
    releaseId: String,
    rules: List<PhotoRuleEntity> = emptyList(),
): ProgramRuleSet = ProgramRuleSet(
    releaseId = releaseId,
    rules = rules,
    variants = emptyList(),
    compositions = emptyList(),
    formats = emptyList(),
    backgrounds = emptyList(),
    sourceImagePolicies = emptyList(),
    requirements = emptyList(),
    sourceLinks = emptyList(),
    sourceEvidence = emptyList(),
)
