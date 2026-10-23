package com.wkq.photo.idlibrary.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface IdPhotoCatalogDao {
    @Query("SELECT active_release_id FROM catalog_state WHERE singleton_id = 1")
    suspend fun activeReleaseId(): String?

    @Query(
        "SELECT * FROM catalog_release WHERE release_id = " +
            "(SELECT active_release_id FROM catalog_state WHERE singleton_id = 1) LIMIT 1",
    )
    suspend fun activeRelease(): CatalogReleaseEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM catalog_release WHERE release_id = :releaseId)")
    suspend fun releaseExists(releaseId: String): Boolean

    @Query("SELECT * FROM catalog_release ORDER BY data_version DESC")
    suspend fun releases(): List<CatalogReleaseEntity>

    @Query("SELECT * FROM locale_pack WHERE release_id = :releaseId ORDER BY locale_tag")
    suspend fun localePacks(releaseId: String): List<LocalePackEntity>

    @Query("SELECT * FROM jurisdiction WHERE release_id = :releaseId AND is_active = 1 ORDER BY code")
    suspend fun jurisdictions(releaseId: String): List<JurisdictionEntity>

    @Query("SELECT * FROM jurisdiction_name WHERE release_id = :releaseId AND locale_tag IN (:localeTags)")
    suspend fun jurisdictionNames(releaseId: String, localeTags: List<String>): List<JurisdictionNameEntity>

    @Query("SELECT * FROM jurisdiction_membership WHERE release_id = :releaseId AND group_code = :groupCode")
    suspend fun jurisdictionMembers(releaseId: String, groupCode: String): List<JurisdictionMembershipEntity>

    @Query("SELECT * FROM jurisdiction_relation WHERE release_id = :releaseId AND parent_code = :parentCode")
    suspend fun jurisdictionChildren(releaseId: String, parentCode: String): List<JurisdictionRelationEntity>

    @Query("SELECT * FROM coverage_status WHERE release_id = :releaseId AND jurisdiction_code = :jurisdictionCode")
    suspend fun coverageStatuses(releaseId: String, jurisdictionCode: String): List<CoverageStatusEntity>

    @Query("SELECT * FROM issuing_authority WHERE release_id = :releaseId AND status != 'deprecated' ORDER BY authority_code")
    suspend fun issuingAuthorities(releaseId: String): List<IssuingAuthorityEntity>

    @Query(
        "SELECT * FROM issuing_authority_text WHERE release_id = :releaseId " +
            "AND authority_id IN (:authorityIds) AND locale_tag IN (:localeTags)",
    )
    suspend fun issuingAuthorityTexts(
        releaseId: String,
        authorityIds: List<String>,
        localeTags: List<String>,
    ): List<IssuingAuthorityTextEntity>

    @Query(
        "SELECT * FROM document_program WHERE release_id = :releaseId " +
            "AND jurisdiction_code = :jurisdictionCode " +
            "AND (:documentType IS NULL OR document_type = :documentType) " +
            "AND status != 'deprecated' ORDER BY program_code",
    )
    suspend fun documentPrograms(
        releaseId: String,
        jurisdictionCode: String,
        documentType: String?,
    ): List<DocumentProgramEntity>

    @Query(
        "SELECT * FROM document_program_text WHERE release_id = :releaseId " +
            "AND program_id IN (:programIds) AND locale_tag IN (:localeTags)",
    )
    suspend fun documentProgramTexts(
        releaseId: String,
        programIds: List<String>,
        localeTags: List<String>,
    ): List<DocumentProgramTextEntity>

    @Query(
        "SELECT * FROM application_channel WHERE release_id = :releaseId " +
            "AND program_id = :programId AND status != 'deprecated' ORDER BY channel_id",
    )
    suspend fun applicationChannels(releaseId: String, programId: String): List<ApplicationChannelEntity>

    @Query(
        "SELECT * FROM application_channel_text WHERE release_id = :releaseId " +
            "AND channel_id IN (:channelIds) AND locale_tag IN (:localeTags)",
    )
    suspend fun applicationChannelTexts(
        releaseId: String,
        channelIds: List<String>,
        localeTags: List<String>,
    ): List<ApplicationChannelTextEntity>

    @Query(
        "SELECT * FROM submission_artifact WHERE release_id = :releaseId " +
            "AND channel_id IN (:channelIds) AND status != 'deprecated'",
    )
    suspend fun submissionArtifacts(releaseId: String, channelIds: List<String>): List<SubmissionArtifactEntity>

    @Query(
        "SELECT * FROM photo_rule WHERE release_id = :releaseId " +
            "AND program_id = :programId AND status != 'deprecated' ORDER BY rule_id",
    )
    suspend fun photoRules(releaseId: String, programId: String): List<PhotoRuleEntity>

    @Query("SELECT * FROM rule_scope WHERE release_id = :releaseId AND rule_id IN (:ruleIds)")
    suspend fun ruleScopes(releaseId: String, ruleIds: List<String>): List<RuleScopeEntity>

    @Query("SELECT * FROM transform_policy WHERE release_id = :releaseId AND rule_id IN (:ruleIds)")
    suspend fun transformPolicies(releaseId: String, ruleIds: List<String>): List<TransformPolicyEntity>

    @Query("SELECT * FROM output_variant WHERE release_id = :releaseId AND rule_id IN (:ruleIds)")
    suspend fun outputVariants(releaseId: String, ruleIds: List<String>): List<OutputVariantEntity>

    @Query("SELECT * FROM variant_capability WHERE release_id = :releaseId AND variant_id IN (:variantIds)")
    suspend fun variantCapabilities(releaseId: String, variantIds: List<String>): List<VariantCapabilityEntity>

    @Query(
        "SELECT * FROM output_variant_text WHERE release_id = :releaseId " +
            "AND variant_id IN (:variantIds) AND locale_tag IN (:localeTags)",
    )
    suspend fun outputVariantTexts(
        releaseId: String,
        variantIds: List<String>,
        localeTags: List<String>,
    ): List<OutputVariantTextEntity>

    @Query("SELECT * FROM composition_rule WHERE release_id = :releaseId AND variant_id IN (:variantIds)")
    suspend fun compositionRules(releaseId: String, variantIds: List<String>): List<CompositionRuleEntity>

    @Query("SELECT * FROM composition_constraint WHERE release_id = :releaseId AND variant_id IN (:variantIds)")
    suspend fun compositionConstraints(
        releaseId: String,
        variantIds: List<String>,
    ): List<CompositionConstraintEntity>

    @Query("SELECT * FROM background_constraint WHERE release_id = :releaseId AND variant_id IN (:variantIds)")
    suspend fun backgroundConstraints(
        releaseId: String,
        variantIds: List<String>,
    ): List<BackgroundConstraintEntity>

    @Query("SELECT * FROM artifact_constraint WHERE release_id = :releaseId AND variant_id IN (:variantIds)")
    suspend fun artifactConstraints(releaseId: String, variantIds: List<String>): List<ArtifactConstraintEntity>

    @Query("SELECT * FROM output_format WHERE release_id = :releaseId AND variant_id IN (:variantIds)")
    suspend fun outputFormats(releaseId: String, variantIds: List<String>): List<OutputFormatEntity>

    @Query("SELECT * FROM output_encoding WHERE release_id = :releaseId AND variant_id IN (:variantIds)")
    suspend fun outputEncodings(releaseId: String, variantIds: List<String>): List<OutputEncodingEntity>

    @Query("SELECT * FROM allowed_background WHERE release_id = :releaseId AND variant_id IN (:variantIds)")
    suspend fun allowedBackgrounds(releaseId: String, variantIds: List<String>): List<AllowedBackgroundEntity>

    @Query("SELECT * FROM source_image_policy WHERE release_id = :releaseId AND rule_id IN (:ruleIds)")
    suspend fun sourceImagePolicies(releaseId: String, ruleIds: List<String>): List<SourceImagePolicyEntity>

    @Query("SELECT * FROM requirement WHERE release_id = :releaseId AND variant_id IN (:variantIds) ORDER BY sort_order")
    suspend fun requirements(releaseId: String, variantIds: List<String>): List<RequirementEntity>

    @Query(
        "SELECT * FROM requirement_text WHERE release_id = :releaseId " +
            "AND requirement_id IN (:requirementIds) AND locale_tag IN (:localeTags)",
    )
    suspend fun requirementTexts(
        releaseId: String,
        requirementIds: List<String>,
        localeTags: List<String>,
    ): List<RequirementTextEntity>

    @Query("SELECT * FROM variant_source_link WHERE release_id = :releaseId AND variant_id IN (:variantIds)")
    suspend fun sourceLinks(releaseId: String, variantIds: List<String>): List<VariantSourceLinkEntity>

    @Query("SELECT * FROM program_source_link WHERE release_id = :releaseId AND program_id = :programId")
    suspend fun programSourceLinks(releaseId: String, programId: String): List<ProgramSourceLinkEntity>

    @Query("SELECT * FROM rule_source_link WHERE release_id = :releaseId AND rule_id IN (:ruleIds)")
    suspend fun ruleSourceLinks(releaseId: String, ruleIds: List<String>): List<RuleSourceLinkEntity>

    @Query(
        "SELECT * FROM coverage_source_link WHERE release_id = :releaseId " +
            "AND jurisdiction_code = :jurisdictionCode AND document_type = :documentType",
    )
    suspend fun coverageSourceLinks(
        releaseId: String,
        jurisdictionCode: String,
        documentType: String,
    ): List<CoverageSourceLinkEntity>

    @Query("SELECT * FROM source_evidence WHERE release_id = :releaseId AND evidence_id IN (:evidenceIds)")
    suspend fun sourceEvidence(releaseId: String, evidenceIds: List<String>): List<SourceEvidenceEntity>

    @Query(
        "SELECT * FROM source_evidence_translation WHERE release_id = :releaseId " +
            "AND evidence_id IN (:evidenceIds) AND locale_tag IN (:localeTags)",
    )
    suspend fun sourceEvidenceTranslations(
        releaseId: String,
        evidenceIds: List<String>,
        localeTags: List<String>,
    ): List<SourceEvidenceTranslationEntity>

    @Query("SELECT * FROM source_evidence_assessment WHERE release_id = :releaseId AND evidence_id IN (:evidenceIds)")
    suspend fun sourceEvidenceAssessments(
        releaseId: String,
        evidenceIds: List<String>,
    ): List<SourceEvidenceAssessmentEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRelease(value: CatalogReleaseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setCatalogState(value: CatalogStateEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLocalePacks(values: List<LocalePackEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertJurisdictions(values: List<JurisdictionEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertJurisdictionNames(values: List<JurisdictionNameEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertJurisdictionMemberships(values: List<JurisdictionMembershipEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertJurisdictionRelations(values: List<JurisdictionRelationEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCoverageStatuses(values: List<CoverageStatusEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertIssuingAuthorities(values: List<IssuingAuthorityEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertIssuingAuthorityTexts(values: List<IssuingAuthorityTextEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDocumentPrograms(values: List<DocumentProgramEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDocumentProgramTexts(values: List<DocumentProgramTextEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertApplicationChannels(values: List<ApplicationChannelEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertApplicationChannelTexts(values: List<ApplicationChannelTextEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSubmissionArtifacts(values: List<SubmissionArtifactEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPhotoRules(values: List<PhotoRuleEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRuleScopes(values: List<RuleScopeEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOutputVariants(values: List<OutputVariantEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOutputVariantTexts(values: List<OutputVariantTextEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVariantCapabilities(values: List<VariantCapabilityEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOutputFormats(values: List<OutputFormatEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOutputEncodings(values: List<OutputEncodingEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAllowedBackgrounds(values: List<AllowedBackgroundEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCompositionRules(values: List<CompositionRuleEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCompositionConstraints(values: List<CompositionConstraintEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBackgroundConstraints(values: List<BackgroundConstraintEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertArtifactConstraints(values: List<ArtifactConstraintEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSourceImagePolicies(values: List<SourceImagePolicyEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTransformPolicies(values: List<TransformPolicyEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRequirements(values: List<RequirementEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRequirementTexts(values: List<RequirementTextEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSourceEvidence(values: List<SourceEvidenceEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSourceEvidenceTranslations(values: List<SourceEvidenceTranslationEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSourceEvidenceAssessments(values: List<SourceEvidenceAssessmentEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProgramSourceLinks(values: List<ProgramSourceLinkEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRuleSourceLinks(values: List<RuleSourceLinkEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCoverageSourceLinks(values: List<CoverageSourceLinkEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSourceLinks(values: List<VariantSourceLinkEntity>)

    @Query("DELETE FROM catalog_release WHERE release_id = :releaseId")
    suspend fun deleteRelease(releaseId: String): Int
}
