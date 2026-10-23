package com.wkq.photo.idlibrary.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "document_program",
    primaryKeys = ["release_id", "program_id"],
    foreignKeys = [
        ForeignKey(
            entity = JurisdictionEntity::class,
            parentColumns = ["release_id", "code"],
            childColumns = ["release_id", "jurisdiction_code"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["release_id", "jurisdiction_code"]),
        Index(value = ["release_id", "jurisdiction_code", "document_type"]),
    ],
)
data class DocumentProgramEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "program_id")
    val programId: String,
    @ColumnInfo(name = "jurisdiction_code")
    val jurisdictionCode: String,
    @ColumnInfo(name = "document_type")
    val documentType: String,
    @ColumnInfo(name = "program_code")
    val programCode: String,
    @ColumnInfo(name = "issuing_authority_code")
    val issuingAuthorityCode: String? = null,
    val status: String,
    @ColumnInfo(name = "effective_from")
    val effectiveFrom: String? = null,
    @ColumnInfo(name = "effective_to")
    val effectiveTo: String? = null,
)

@Entity(
    tableName = "document_program_text",
    primaryKeys = ["release_id", "program_id", "locale_tag"],
    foreignKeys = [
        ForeignKey(
            entity = DocumentProgramEntity::class,
            parentColumns = ["release_id", "program_id"],
            childColumns = ["release_id", "program_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["release_id", "program_id"]),
        Index(value = ["release_id", "locale_tag"]),
    ],
)
data class DocumentProgramTextEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "program_id")
    val programId: String,
    @ColumnInfo(name = "locale_tag")
    val localeTag: String,
    val name: String,
    val description: String? = null,
    @ColumnInfo(name = "translation_status")
    val translationStatus: String,
)

@Entity(
    tableName = "application_channel",
    primaryKeys = ["release_id", "channel_id"],
    foreignKeys = [
        ForeignKey(
            entity = DocumentProgramEntity::class,
            parentColumns = ["release_id", "program_id"],
            childColumns = ["release_id", "program_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["release_id", "program_id"]),
        Index(value = ["release_id", "program_id", "channel_type"]),
    ],
)
data class ApplicationChannelEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "channel_id")
    val channelId: String,
    @ColumnInfo(name = "program_id")
    val programId: String,
    @ColumnInfo(name = "channel_type")
    val channelType: String,
    @ColumnInfo(name = "submission_url")
    val submissionUrl: String? = null,
    @ColumnInfo(name = "is_official")
    val isOfficial: Boolean,
    val status: String,
)

@Entity(
    tableName = "application_channel_text",
    primaryKeys = ["release_id", "channel_id", "locale_tag"],
    foreignKeys = [
        ForeignKey(
            entity = ApplicationChannelEntity::class,
            parentColumns = ["release_id", "channel_id"],
            childColumns = ["release_id", "channel_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["release_id", "channel_id"])],
)
data class ApplicationChannelTextEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "channel_id")
    val channelId: String,
    @ColumnInfo(name = "locale_tag")
    val localeTag: String,
    val name: String,
    val instructions: String? = null,
    @ColumnInfo(name = "translation_status")
    val translationStatus: String,
)
