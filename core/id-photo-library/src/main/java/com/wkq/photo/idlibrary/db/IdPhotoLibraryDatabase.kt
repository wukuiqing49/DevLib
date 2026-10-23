package com.wkq.photo.idlibrary.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CatalogReleaseEntity::class,
        CatalogStateEntity::class,
        LocalePackEntity::class,
        JurisdictionEntity::class,
        JurisdictionNameEntity::class,
        JurisdictionMembershipEntity::class,
        JurisdictionRelationEntity::class,
        CoverageStatusEntity::class,
        IssuingAuthorityEntity::class,
        IssuingAuthorityTextEntity::class,
        DocumentProgramEntity::class,
        DocumentProgramTextEntity::class,
        ApplicationChannelEntity::class,
        ApplicationChannelTextEntity::class,
        SubmissionArtifactEntity::class,
        PhotoRuleEntity::class,
        RuleScopeEntity::class,
        OutputVariantEntity::class,
        OutputVariantTextEntity::class,
        VariantCapabilityEntity::class,
        OutputFormatEntity::class,
        OutputEncodingEntity::class,
        AllowedBackgroundEntity::class,
        CompositionRuleEntity::class,
        CompositionConstraintEntity::class,
        BackgroundConstraintEntity::class,
        ArtifactConstraintEntity::class,
        SourceImagePolicyEntity::class,
        TransformPolicyEntity::class,
        RequirementEntity::class,
        RequirementTextEntity::class,
        SourceEvidenceEntity::class,
        SourceEvidenceTranslationEntity::class,
        SourceEvidenceAssessmentEntity::class,
        ProgramSourceLinkEntity::class,
        RuleSourceLinkEntity::class,
        CoverageSourceLinkEntity::class,
        VariantSourceLinkEntity::class,
    ],
    version = 3,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
    ],
    exportSchema = true,
)
abstract class IdPhotoLibraryDatabase : RoomDatabase() {
    abstract fun catalogDao(): IdPhotoCatalogDao

    companion object {
        const val DATABASE_NAME = "id-photo-library.db"

        @Volatile
        private var instance: IdPhotoLibraryDatabase? = null

        fun getInstance(context: Context): IdPhotoLibraryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                IdPhotoLibraryDatabase::class.java,
                DATABASE_NAME,
            ).build()
                .also { instance = it }
        }

        fun closeInstance() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}
