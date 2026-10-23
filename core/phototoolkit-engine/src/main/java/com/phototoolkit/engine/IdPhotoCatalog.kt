package com.phototoolkit.engine

import android.content.Context
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class IdPhotoSizeUnit { MM, INCH, PX }

enum class IdPhotoDelivery { DIGITAL, PRINT }

enum class IdPhotoDocumentType { PASSPORT, VISA, NATIONAL_ID, DRIVER_LICENSE, GENERAL }

enum class IdPhotoJurisdictionKind { COUNTRY_OR_AREA, REGIONAL_GROUP, PRESET }

enum class IdPhotoCoverageLevel { VERIFIED, REFERENCE, MISSING }

enum class IdPhotoDimensionMode { EXACT, MINIMUM, RANGE }

enum class IdPhotoVerificationStatus {
    VERIFIED,
    CHANNEL_VERIFIED,
    /** 官方文件或官方页面的二手转述，当前没有直接核验原始官网。 */
    OFFICIAL_REPORTED,
    REFERENCE,
    DEPRECATED,
}

enum class IdPhotoEditPolicy { ALLOWED, RESTRICTED, PROHIBITED }

enum class IdPhotoCapturePolicy {
    SELF_CAPTURE_ALLOWED,
    ASSISTED_CAPTURE_RECOMMENDED,
    COMMERCIAL_PHOTOGRAPHER_REQUIRED,
    AUTHORIZED_PROVIDER_REQUIRED,
}

enum class IdPhotoSourceImagePolicy {
    GENERATED_FILE_ALLOWED,
    ORIGINAL_CAMERA_FILE_REQUIRED,
    SCANNED_PRINT_ALLOWED,
}

enum class IdPhotoApplicationChannel { ONLINE, MOBILE_APP, MAIL, IN_PERSON, KIOSK, PHOTO_TOOL }

enum class IdPhotoHeadMeasurement { FACE_BOX, CROWN_TO_CHIN }

enum class IdPhotoBottomPolicy { PRESERVE_COMPOSITION, PREFER_TOUCH }

enum class IdPhotoTopPolicy { FIXED_REFERENCE, AUTO_COMPOSITION }

data class IdPhotoSource(
    val id: String,
    val authority: String,
    val title: String,
    val url: String?,
    val verifiedAt: String?,
) {
    fun isFresh(
        asOfEpochMillis: Long = System.currentTimeMillis(),
        maxAgeDays: Int = DEFAULT_FRESHNESS_DAYS,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Boolean {
        require(maxAgeDays >= 0)
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
            isLenient = false
            this.timeZone = timeZone
        }
        val verifiedText = verifiedAt ?: return false
        val verifiedDay = runCatching { formatter.parse(verifiedText) }.getOrNull() ?: return false
        if (formatter.format(verifiedDay) != verifiedText) return false
        val asOfDay = formatter.parse(formatter.format(Date(asOfEpochMillis))) ?: return false
        val ageDays = (asOfDay.time - verifiedDay.time) / MILLIS_PER_DAY
        return ageDays in 0L..maxAgeDays.toLong()
    }

    companion object {
        const val DEFAULT_FRESHNESS_DAYS = 180
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}

data class IdPhotoOutputRule(
    val width: BigDecimal,
    val height: BigDecimal,
    val unit: IdPhotoSizeUnit,
    val dimensionMode: IdPhotoDimensionMode,
    val dpi: Int,
    val minWidth: BigDecimal? = null,
    val minHeight: BigDecimal? = null,
    val maxWidth: BigDecimal? = null,
    val maxHeight: BigDecimal? = null,
    val minBytes: Long? = null,
    val maxBytes: Long? = null,
    val formats: Set<IdPhotoOutputFormat> = setOf(IdPhotoOutputFormat.JPEG),
    val colorSpace: String = "sRGB",
) {
    init {
        require(width.signum() > 0 && height.signum() > 0)
        require(dpi in 72..1200)
        require(minBytes == null || minBytes > 0)
        require(maxBytes == null || maxBytes > 0)
        require(minBytes == null || maxBytes == null || minBytes <= maxBytes)
        require(formats.isNotEmpty())
        if (dimensionMode == IdPhotoDimensionMode.RANGE) {
            require(minWidth != null && minHeight != null && maxWidth != null && maxHeight != null)
            require(minWidth.signum() > 0 && minHeight.signum() > 0)
            require(maxWidth >= minWidth && maxHeight >= minHeight)
            require(width in minWidth..maxWidth && height in minHeight..maxHeight)
        }
    }

    fun widthPx(): Int = IdPhotoUnitConverter.toPixels(width, unit, dpi)

    fun heightPx(): Int = IdPhotoUnitConverter.toPixels(height, unit, dpi)

    fun physicalWidthMm(): BigDecimal? = IdPhotoUnitConverter.toMillimeters(width, unit, dpi)

    fun physicalHeightMm(): BigDecimal? = IdPhotoUnitConverter.toMillimeters(height, unit, dpi)
}

object IdPhotoUnitConverter {
    val MILLIMETERS_PER_INCH: BigDecimal = BigDecimal("25.4")

    fun toPixels(value: BigDecimal, unit: IdPhotoSizeUnit, dpi: Int): Int {
        require(value.signum() > 0)
        require(dpi in 72..1200)
        val pixels = when (unit) {
            IdPhotoSizeUnit.PX -> value
            IdPhotoSizeUnit.MM -> value.multiply(BigDecimal.valueOf(dpi.toLong()))
                .divide(MILLIMETERS_PER_INCH, 12, RoundingMode.HALF_UP)
            IdPhotoSizeUnit.INCH -> value.multiply(BigDecimal.valueOf(dpi.toLong()))
        }
        return pixels.setScale(0, RoundingMode.HALF_UP).intValueExact()
    }

    fun toMillimeters(value: BigDecimal, unit: IdPhotoSizeUnit, dpi: Int): BigDecimal? = when (unit) {
        IdPhotoSizeUnit.MM -> value
        IdPhotoSizeUnit.INCH -> value.multiply(MILLIMETERS_PER_INCH)
        IdPhotoSizeUnit.PX -> null
    }
}

data class IdPhotoVariant(
    val id: String,
    val documentId: String,
    val displayName: String,
    val delivery: IdPhotoDelivery,
    val output: IdPhotoOutputRule,
    val backgroundHex: String,
    val allowedBackgroundHexes: List<String>,
    val faceHeightRatio: Double,
    val faceTopRatio: Double,
    val topPolicy: IdPhotoTopPolicy,
    val faceHeightMinRatio: Double,
    val faceHeightMaxRatio: Double,
    val faceTopMinRatio: Double,
    val faceTopMaxRatio: Double,
    val faceHeightConstraint: IdPhotoConstraintLevel,
    val faceTopConstraint: IdPhotoConstraintLevel,
    val eyeLineRatio: Double?,
    val eyeLineTolerance: Double,
    val eyeLineConstraint: IdPhotoConstraintLevel,
    val minFaceWidthRatio: Float,
    val maxHeadPoseDegrees: Float,
    val minEyeOpenProbability: Float?,
    val headMeasurement: IdPhotoHeadMeasurement,
    val faceCenterXRatio: Double,
    val faceCenterXTolerance: Double,
    val faceCenterConstraint: IdPhotoConstraintLevel,
    val shoulderLineRatio: Double?,
    val shoulderLineTolerance: Double,
    val shoulderLineConstraint: IdPhotoConstraintLevel,
    val maxUpscaleRatio: Double,
    val portraitBottomRatio: Double,
    val maxPortraitWidthRatio: Double,
    val bottomPolicy: IdPhotoBottomPolicy,
    val strictComposition: Boolean,
    val editPolicy: IdPhotoEditPolicy,
    val capturePolicy: IdPhotoCapturePolicy,
    val sourceImagePolicies: Set<IdPhotoSourceImagePolicy>,
    val applicationChannels: Set<IdPhotoApplicationChannel>,
    val requirements: List<String>,
    val source: IdPhotoSource,
    val additionalSources: List<IdPhotoSource>,
    val verificationStatus: IdPhotoVerificationStatus,
    val ruleVersion: String,
    val reviewedAt: String,
    val effectiveFrom: String?,
    val effectiveTo: String?,
) {
    init {
        require(id.isNotBlank() && documentId.isNotBlank())
        require((listOf(backgroundHex) + allowedBackgroundHexes).all { it.matches(HEX_COLOR) })
        require(faceHeightMinRatio in 0.1..faceHeightRatio)
        require(faceHeightMaxRatio in faceHeightRatio..0.9)
        require(faceTopMinRatio in 0.0..faceTopRatio)
        require(faceTopMaxRatio in faceTopRatio..<0.9)
        require(eyeLineRatio == null || eyeLineRatio in 0.0..1.0)
        require(eyeLineTolerance in 0.0..0.5)
        require(faceCenterXRatio in 0.0..1.0)
        require(faceCenterXTolerance in 0.0..0.25)
        require(shoulderLineRatio == null || shoulderLineRatio in 0.5..1.0)
        require(shoulderLineTolerance in 0.0..0.25)
        require(maxUpscaleRatio in 1.0..4.0)
        require(portraitBottomRatio in 0.5..1.0)
        require(maxPortraitWidthRatio in 0.5..3.0)
        require(sourceImagePolicies.isNotEmpty())
        require((listOf(source) + additionalSources).map { it.id }.distinct().size == additionalSources.size + 1)
        require(ruleVersion.isNotBlank())
        require(isValidIsoDate(reviewedAt))
        require(effectiveFrom == null || isValidIsoDate(effectiveFrom))
        require(effectiveTo == null || isValidIsoDate(effectiveTo))
        require(effectiveFrom == null || effectiveTo == null || effectiveFrom <= effectiveTo)
    }

    val sources: List<IdPhotoSource> get() = listOf(source) + additionalSources

    /** 官方规则只有在有效期内且仍处于产品复核窗口时，才允许作为生成依据。 */
    fun isCurrentForGeneration(
        asOfEpochMillis: Long = System.currentTimeMillis(),
        maxSourceAgeDays: Int = IdPhotoSource.DEFAULT_FRESHNESS_DAYS,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Boolean {
        require(maxSourceAgeDays >= 0)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
            isLenient = false
            this.timeZone = timeZone
        }
        val today = dateFormat.format(Date(asOfEpochMillis))
        if (effectiveFrom?.let { today < it } == true) return false
        if (effectiveTo?.let { today > it } == true) return false
        val official = verificationStatus == IdPhotoVerificationStatus.VERIFIED ||
            verificationStatus == IdPhotoVerificationStatus.CHANNEL_VERIFIED
        return !official || sources.all {
            it.isFresh(asOfEpochMillis, maxSourceAgeDays, timeZone)
        }
    }

    fun toSpec(): IdPhotoSpec = IdPhotoSpec(
        widthPx = output.widthPx(),
        heightPx = output.heightPx(),
        faceHeightRatio = faceHeightRatio,
        faceTopRatio = faceTopRatio,
        topPolicy = topPolicy,
        faceHeightMinRatio = faceHeightMinRatio,
        faceHeightMaxRatio = faceHeightMaxRatio,
        faceTopMinRatio = faceTopMinRatio,
        faceTopMaxRatio = faceTopMaxRatio,
        faceHeightConstraint = faceHeightConstraint,
        faceTopConstraint = faceTopConstraint,
        eyeLineRatio = eyeLineRatio,
        eyeLineTolerance = eyeLineTolerance,
        eyeLineConstraint = eyeLineConstraint,
        minFaceWidthRatio = minFaceWidthRatio,
        maxHeadPoseDegrees = maxHeadPoseDegrees,
        minEyeOpenProbability = minEyeOpenProbability,
        allowedBackgroundColors = allowedBackgroundHexes.map(::parseColor),
        allowedOutputFormats = output.formats,
        minOutputBytes = output.minBytes,
        maxOutputBytes = output.maxBytes,
        headMeasurement = headMeasurement,
        faceCenterXRatio = faceCenterXRatio,
        faceCenterXTolerance = faceCenterXTolerance,
        faceCenterConstraint = faceCenterConstraint,
        shoulderLineRatio = shoulderLineRatio,
        shoulderLineTolerance = shoulderLineTolerance,
        shoulderLineConstraint = shoulderLineConstraint,
        maxUpscaleRatio = maxUpscaleRatio,
        portraitBottomRatio = portraitBottomRatio,
        maxPortraitWidthRatio = maxPortraitWidthRatio,
        bottomPolicy = bottomPolicy,
        strictComposition = strictComposition,
        outputDpi = output.dpi,
        delivery = delivery,
        dimensionMode = output.dimensionMode,
        editPolicy = editPolicy,
    )

    fun backgroundColor(): IdPhotoColor = parseColor(backgroundHex)

    private fun parseColor(hex: String): IdPhotoColor {
        val value = hex.removePrefix("#").toInt(16)
        return IdPhotoColor(value shr 16 and 0xFF, value shr 8 and 0xFF, value and 0xFF)
    }

    private companion object {
        val HEX_COLOR = Regex("^#[0-9A-Fa-f]{6}$")
    }
}

data class IdPhotoJurisdiction(
    val code: String,
    val kind: IdPhotoJurisdictionKind,
    val displayName: String,
    val memberCodes: List<String> = emptyList(),
) {
    init {
        require(code.matches(Regex("^[A-Z]{2,16}$")))
        require(displayName.isNotBlank())
        require(memberCodes.distinct().size == memberCodes.size)
        require(memberCodes.all { it.matches(Regex("^[A-Z]{2}$")) })
        require(kind == IdPhotoJurisdictionKind.REGIONAL_GROUP || memberCodes.isEmpty())
    }
}

data class IdPhotoRegistrySource(
    val authority: String,
    val url: String,
    val reviewedAt: String,
) {
    init {
        require(authority.isNotBlank())
        require(url.startsWith("https://"))
        require(isValidIsoDate(reviewedAt))
    }
}

data class IdPhotoDocumentCoverage(
    val jurisdiction: IdPhotoJurisdiction,
    val documentType: IdPhotoDocumentType,
    val level: IdPhotoCoverageLevel,
    val documentCount: Int,
    val variantCount: Int,
)

data class IdPhotoDocument(
    val id: String,
    val regionCode: String,
    val regionName: String,
    val documentType: IdPhotoDocumentType,
    val displayName: String,
    val variants: List<IdPhotoVariant>,
) {
    init {
        require(id.isNotBlank() && regionCode.isNotBlank())
        require(variants.isNotEmpty())
        require(variants.all { it.documentId == id })
        require(variants.map { it.id }.distinct().size == variants.size)
    }
}

data class IdPhotoCatalog(
    val schemaVersion: Int,
    val catalogVersion: Int,
    val documents: List<IdPhotoDocument>,
    val jurisdictionListVersion: String? = null,
    val jurisdictionStandard: String? = null,
    val jurisdictionSource: IdPhotoRegistrySource? = null,
    val jurisdictions: List<IdPhotoJurisdiction> = documents
        .distinctBy { it.regionCode }
        .map {
            IdPhotoJurisdiction(
                code = it.regionCode,
                kind = IdPhotoJurisdictionKind.COUNTRY_OR_AREA,
                displayName = it.regionName,
            )
        },
) {
    init {
        require(documents.isNotEmpty()) { "ID photo catalog cannot be empty" }
        require(documents.map { it.id }.distinct().size == documents.size) { "Duplicate ID photo document" }
        val variants = documents.flatMap { it.variants }
        require(variants.map { it.id }.distinct().size == variants.size) { "Duplicate ID photo variant" }
        require(jurisdictions.map { it.code }.distinct().size == jurisdictions.size) {
            "Duplicate ID photo jurisdiction"
        }
        require(documents.all { document -> jurisdictions.any { it.code == document.regionCode } }) {
            "ID photo document references an unknown jurisdiction"
        }
        val countryCodes = jurisdictions
            .filter { it.kind == IdPhotoJurisdictionKind.COUNTRY_OR_AREA }
            .mapTo(hashSetOf()) { it.code }
        require(jurisdictions.filter { it.kind == IdPhotoJurisdictionKind.REGIONAL_GROUP }
            .all { group -> group.memberCodes.all(countryCodes::contains) }) {
            "Regional group references an unknown country or area"
        }
    }

    fun regions(): List<Pair<String, String>> = documents
        .distinctBy { it.regionCode }
        .map { it.regionCode to it.regionName }

    fun coverage(
        documentTypes: Set<IdPhotoDocumentType> = COMMERCIAL_DOCUMENT_TYPES,
    ): List<IdPhotoDocumentCoverage> = jurisdictions
        .filter { it.kind != IdPhotoJurisdictionKind.PRESET }
        .flatMap { jurisdiction ->
            documentTypes.map { documentType ->
                val matchingDocuments = documents.filter {
                    it.regionCode == jurisdiction.code && it.documentType == documentType
                }
                val variants = matchingDocuments.flatMap { it.variants }
                    .filter { it.verificationStatus != IdPhotoVerificationStatus.DEPRECATED }
                val level = when {
                    variants.any {
                        it.verificationStatus == IdPhotoVerificationStatus.VERIFIED ||
                            it.verificationStatus == IdPhotoVerificationStatus.CHANNEL_VERIFIED
                    } -> IdPhotoCoverageLevel.VERIFIED
                    variants.any { it.verificationStatus == IdPhotoVerificationStatus.OFFICIAL_REPORTED } ->
                        IdPhotoCoverageLevel.REFERENCE
                    variants.isNotEmpty() -> IdPhotoCoverageLevel.REFERENCE
                    else -> IdPhotoCoverageLevel.MISSING
                }
                IdPhotoDocumentCoverage(
                    jurisdiction = jurisdiction,
                    documentType = documentType,
                    level = level,
                    documentCount = matchingDocuments.size,
                    variantCount = variants.size,
                )
            }
        }

    companion object {
        val COMMERCIAL_DOCUMENT_TYPES = setOf(
            IdPhotoDocumentType.PASSPORT,
            IdPhotoDocumentType.VISA,
            IdPhotoDocumentType.NATIONAL_ID,
        )
    }
}

object IdPhotoCatalogRepository {
    private const val CATALOG_ASSET = "id-photo/catalog.json"
    private const val SOURCES_ASSET = "id-photo/sources.json"
    private const val JURISDICTIONS_ASSET = "id-photo/jurisdictions.json"
    private const val I18N_DIRECTORY = "id-photo/i18n"
    private const val FALLBACK_LOCALE = "en"

    fun load(context: Context, locale: Locale): IdPhotoCatalog {
        val catalogJson = context.assets.readText(CATALOG_ASSET)
        val sourcesJson = context.assets.readText(SOURCES_ASSET)
        val jurisdictionsJson = context.assets.readText(JURISDICTIONS_ASSET)
        val fallbackJson = readLocalization(context, FALLBACK_LOCALE)
        val localizedJson = readLocalization(context, selectLocale(context, locale))
        return parseInternal(
            catalogJson = catalogJson,
            sourcesJson = sourcesJson,
            fallbackJson = fallbackJson,
            localizedJson = localizedJson,
            jurisdictionsJson = jurisdictionsJson,
            locale = locale,
        )
    }

    fun parse(
        catalogJson: String,
        sourcesJson: String,
        fallbackJson: String,
        localizedJson: String,
    ): IdPhotoCatalog = parseInternal(
        catalogJson = catalogJson,
        sourcesJson = sourcesJson,
        fallbackJson = fallbackJson,
        localizedJson = localizedJson,
        jurisdictionsJson = null,
        locale = Locale.ENGLISH,
    )

    private fun parseInternal(
        catalogJson: String,
        sourcesJson: String,
        fallbackJson: String,
        localizedJson: String,
        jurisdictionsJson: String?,
        locale: Locale,
    ): IdPhotoCatalog {
        val root = JSONObject(catalogJson)
        val translated = parseStrings(fallbackJson) + parseStrings(localizedJson)
        val sources = parseSources(sourcesJson)
        val profiles = parseRuleProfiles(root)
        val defaultProfileId = root.getString("defaultRuleProfileId")
        val rawDocuments = root.getJSONArray("documents")
        val documents = buildList(rawDocuments.length()) {
            for (documentIndex in 0 until rawDocuments.length()) {
                val document = rawDocuments.getJSONObject(documentIndex)
                val documentId = document.getString("id")
                val rawVariants = document.getJSONArray("variants")
                val variants = buildList(rawVariants.length()) {
                    for (variantIndex in 0 until rawVariants.length()) {
                        val variant = rawVariants.getJSONObject(variantIndex)
                        val output = parseOutput(variant.getJSONObject("output"))
                        val profile = requireNotNull(profiles[variant.optString("ruleProfileId", defaultProfileId)]) {
                            "Unknown ID photo rule profile"
                        }
                        val composition = variant.optJSONObject("composition")
                        val hasExplicitTopMargin = composition?.has("topMarginRatio") == true
                        val faceHeightRatio = composition?.optDouble("faceHeightRatio", profile.faceHeightRatio)
                            ?: profile.faceHeightRatio
                        val faceTopRatio = if (hasExplicitTopMargin) {
                            composition?.optDouble("topMarginRatio", profile.faceTopRatio)
                                ?: profile.faceTopRatio
                        } else {
                            0.0
                        }
                        val sourceIds = variant.optJSONArray("sourceIds")?.toStringList()
                            ?: listOf(variant.getString("sourceId"))
                        require(sourceIds.isNotEmpty()) { "ID photo rule must have at least one source" }
                        val variantSources = sourceIds.map { sourceId ->
                            requireNotNull(sources[sourceId]) { "Unknown ID photo source: $sourceId" }
                        }
                        val status = enumValue<IdPhotoVerificationStatus>(variant.getString("status"))
                        if (status == IdPhotoVerificationStatus.OFFICIAL_REPORTED) {
                            require(
                                composition?.optionalString("faceHeightConstraint") != "hard" &&
                                    composition?.optionalString("topMarginConstraint") != "hard",
                            ) {
                                "Officially reported ID photo rules must keep composition constraints soft: ${variant.getString("id")}"
                            }
                        }
                        variantSources.forEach { validateSource(status, it) }
                        val background = variant.optJSONObject("background") ?: JSONObject("{\"color\":\"#FFFFFF\"}")
                        add(
                            IdPhotoVariant(
                                id = variant.getString("id"),
                                documentId = documentId,
                                displayName = translated[variant.getString("displayNameKey")]
                                    ?: variant.getString("displayNameKey"),
                                delivery = enumValue(variant.getString("delivery")),
                                output = output,
                                backgroundHex = background.getString("color"),
                                allowedBackgroundHexes = background.optJSONArray("allowedColors")?.toStringList()
                                    ?: listOf(background.getString("color")),
                                faceHeightRatio = faceHeightRatio,
                                faceTopRatio = faceTopRatio,
                                faceHeightMinRatio = composition?.optJSONObject("faceHeightRange")?.getDecimal("min")?.toDouble()
                                    ?: (faceHeightRatio - profile.faceHeightTolerance).coerceAtLeast(0.1),
                                faceHeightMaxRatio = composition?.optJSONObject("faceHeightRange")?.getDecimal("max")?.toDouble()
                                    ?: (faceHeightRatio + profile.faceHeightTolerance).coerceAtMost(0.9),
                                faceTopMinRatio = if (hasExplicitTopMargin) {
                                    composition?.optJSONObject("topMarginRange")?.getDecimal("min")?.toDouble()
                                        ?: (faceTopRatio - profile.topMarginTolerance).coerceAtLeast(0.0)
                                } else {
                                    0.0
                                },
                                faceTopMaxRatio = if (hasExplicitTopMargin) {
                                    composition?.optJSONObject("topMarginRange")?.getDecimal("max")?.toDouble()
                                        ?: (faceTopRatio + profile.topMarginTolerance).coerceAtMost(0.89)
                                } else {
                                    0.89
                                },
                                topPolicy = if (hasExplicitTopMargin) {
                                    IdPhotoTopPolicy.FIXED_REFERENCE
                                } else {
                                    IdPhotoTopPolicy.AUTO_COMPOSITION
                                },
                                faceHeightConstraint = composition?.optionalString("faceHeightConstraint")
                                    ?.let { enumValue<IdPhotoConstraintLevel>(it) }
                                    ?: profile.faceHeightConstraint,
                                faceTopConstraint = composition?.optionalString("topMarginConstraint")
                                    ?.let { enumValue<IdPhotoConstraintLevel>(it) }
                                    ?: profile.faceTopConstraint,
                                eyeLineRatio = composition?.optionalDouble("eyeLineRatio") ?: profile.eyeLineRatio,
                                eyeLineTolerance = composition?.optionalDouble("eyeLineTolerance") ?: profile.eyeLineTolerance,
                                eyeLineConstraint = composition?.optionalString("eyeLineConstraint")
                                    ?.let { enumValue<IdPhotoConstraintLevel>(it) }
                                    ?: profile.eyeLineConstraint,
                                minFaceWidthRatio = profile.minFaceWidthRatio,
                                maxHeadPoseDegrees = profile.maxHeadPoseDegrees,
                                minEyeOpenProbability = profile.minEyeOpenProbability,
                                headMeasurement = profile.headMeasurement,
                                faceCenterXRatio = profile.faceCenterXRatio,
                                faceCenterXTolerance = profile.faceCenterXTolerance,
                                faceCenterConstraint = profile.faceCenterConstraint,
                                shoulderLineRatio = composition?.optionalDouble("shoulderLineRatio")
                                    ?: profile.shoulderLineRatio,
                                shoulderLineTolerance = composition?.optionalDouble("shoulderLineTolerance")
                                    ?: profile.shoulderLineTolerance,
                                shoulderLineConstraint = composition?.optionalString("shoulderLineConstraint")
                                    ?.let { enumValue<IdPhotoConstraintLevel>(it) }
                                    ?: profile.shoulderLineConstraint,
                                maxUpscaleRatio = composition?.optionalDouble("maxUpscaleRatio")
                                    ?: profile.maxUpscaleRatio,
                                portraitBottomRatio = profile.portraitBottomRatio,
                                maxPortraitWidthRatio = profile.maxPortraitWidthRatio,
                                bottomPolicy = profile.bottomPolicy,
                                strictComposition = profile.strictComposition,
                                editPolicy = enumValue(variant.optString("editPolicy", "allowed")),
                                capturePolicy = enumValue(variant.optString("capturePolicy", "self_capture_allowed")),
                                sourceImagePolicies = variant.optJSONArray("sourceImagePolicies")?.toStringList()
                                    ?.mapTo(linkedSetOf()) { enumValue<IdPhotoSourceImagePolicy>(it) }
                                    ?: setOf(IdPhotoSourceImagePolicy.GENERATED_FILE_ALLOWED),
                                applicationChannels = variant.optJSONArray("applicationChannels")?.toStringList()
                                    ?.mapTo(linkedSetOf()) { enumValue<IdPhotoApplicationChannel>(it) }
                                    ?: emptySet(),
                                requirements = variant.optJSONArray("requirementKeys")?.toStringList()
                                    ?.map { translated[it] ?: it }
                                    ?: emptyList(),
                                source = variantSources.first(),
                                additionalSources = variantSources.drop(1),
                                verificationStatus = status,
                                ruleVersion = variant.optString(
                                    "ruleVersion",
                                    root.getString("defaultRuleVersion"),
                                ),
                                reviewedAt = variant.optionalString("reviewedAt")
                                    ?: variantSources.mapNotNull { it.verifiedAt }.minOrNull()
                                    ?: root.getString("defaultReviewedAt"),
                                effectiveFrom = variant.optionalString("effectiveFrom"),
                                effectiveTo = variant.optionalString("effectiveTo"),
                            ),
                        )
                    }
                }
                add(
                    IdPhotoDocument(
                        id = documentId,
                        regionCode = document.getString("regionCode"),
                        regionName = translated[document.getString("regionNameKey")]
                            ?: document.getString("regionNameKey"),
                        documentType = enumValue(document.getString("documentType")),
                        displayName = translated[document.getString("displayNameKey")]
                            ?: document.getString("displayNameKey"),
                        variants = variants,
                    ),
                )
            }
        }
        val registry = jurisdictionsJson?.let {
            parseJurisdictions(it, translated, locale)
        }
        val jurisdictions = registry?.jurisdictions ?: documents
            .distinctBy { it.regionCode }
            .map {
                IdPhotoJurisdiction(
                    code = it.regionCode,
                    kind = IdPhotoJurisdictionKind.COUNTRY_OR_AREA,
                    displayName = it.regionName,
                )
            }
        return IdPhotoCatalog(
            schemaVersion = root.getInt("schemaVersion"),
            catalogVersion = root.getInt("catalogVersion"),
            documents = documents,
            jurisdictionListVersion = registry?.listVersion,
            jurisdictionStandard = registry?.standard,
            jurisdictionSource = registry?.source,
            jurisdictions = jurisdictions,
        )
    }

    private fun parseJurisdictions(
        json: String,
        translated: Map<String, String>,
        locale: Locale,
    ): ParsedJurisdictionRegistry {
        val root = JSONObject(json)
        val values = root.getJSONArray("jurisdictions")
        val jurisdictions = buildList(values.length()) {
            for (index in 0 until values.length()) {
                val item = values.getJSONObject(index)
                val code = item.getString("code")
                val displayNameKey = item.optionalString("displayNameKey")
                val localizedCountryName = Locale("", code).getDisplayCountry(locale)
                add(
                    IdPhotoJurisdiction(
                        code = code,
                        kind = enumValue(item.getString("kind")),
                        displayName = displayNameKey?.let { translated[it] ?: it }
                            ?: localizedCountryName.takeIf { it.isNotBlank() && it != code }
                            ?: code,
                        memberCodes = item.optJSONArray("memberCodes")?.toStringList().orEmpty(),
                    ),
                )
            }
        }
        return ParsedJurisdictionRegistry(
            listVersion = root.getString("listVersion"),
            standard = root.getString("standard"),
            source = root.getJSONObject("source").let { source ->
                IdPhotoRegistrySource(
                    authority = source.getString("authority"),
                    url = source.getString("url"),
                    reviewedAt = source.getString("reviewedAt"),
                )
            },
            jurisdictions = jurisdictions,
        )
    }

    private fun parseOutput(value: JSONObject): IdPhotoOutputRule = IdPhotoOutputRule(
        width = value.getDecimal("width"),
        height = value.getDecimal("height"),
        unit = enumValue(value.getString("unit")),
        dimensionMode = enumValue(value.optString("dimensionMode", "exact")),
        dpi = value.optInt("dpi", 300),
        minWidth = value.optionalDecimal("minWidth"),
        minHeight = value.optionalDecimal("minHeight"),
        maxWidth = value.optionalDecimal("maxWidth"),
        maxHeight = value.optionalDecimal("maxHeight"),
        minBytes = value.optionalLong("minBytes"),
        maxBytes = value.optionalLong("maxBytes"),
        formats = value.optJSONArray("formats")?.toStringList()
            ?.mapTo(linkedSetOf()) { enumValue<IdPhotoOutputFormat>(it) }
            ?: setOf(IdPhotoOutputFormat.JPEG),
        colorSpace = value.optString("colorSpace", "sRGB"),
    )

    private fun parseSources(json: String): Map<String, IdPhotoSource> {
        val values = JSONObject(json).getJSONArray("sources")
        return buildMap {
            for (index in 0 until values.length()) {
                val item = values.getJSONObject(index)
                val source = IdPhotoSource(
                    id = item.getString("id"),
                    authority = item.getString("authority"),
                    title = item.getString("title"),
                    url = item.optionalString("url"),
                    verifiedAt = item.optionalString("verifiedAt"),
                )
                check(put(source.id, source) == null) { "Duplicate ID photo source: ${source.id}" }
            }
        }
    }

    private fun validateSource(status: IdPhotoVerificationStatus, source: IdPhotoSource) {
        if (status == IdPhotoVerificationStatus.VERIFIED || status == IdPhotoVerificationStatus.CHANNEL_VERIFIED) {
            require(!source.url.isNullOrBlank() && !source.verifiedAt.isNullOrBlank()) {
                "Verified ID photo rule requires an official URL and verification date: ${source.id}"
            }
            require(source.url.startsWith("https://")) { "Official source must use HTTPS: ${source.id}" }
        }
    }

    private fun parseRuleProfiles(root: JSONObject): Map<String, IdPhotoRuleProfile> {
        val values = root.getJSONArray("ruleProfiles")
        return buildMap {
            for (index in 0 until values.length()) {
                val item = values.getJSONObject(index)
                val profile = IdPhotoRuleProfile(
                    id = item.getString("id"),
                    faceHeightRatio = item.getDouble("faceHeightRatio"),
                    faceTopRatio = item.getDouble("faceTopRatio"),
                    faceHeightTolerance = item.getDouble("faceHeightTolerance"),
                    topMarginTolerance = item.getDouble("topMarginTolerance"),
                    faceHeightConstraint = enumValue(item.optString("faceHeightConstraint", "soft")),
                    faceTopConstraint = enumValue(item.optString("topMarginConstraint", "soft")),
                    eyeLineRatio = item.optionalDouble("eyeLineRatio"),
                    eyeLineTolerance = item.getDouble("eyeLineTolerance"),
                    eyeLineConstraint = enumValue(item.optString("eyeLineConstraint", "soft")),
                    minFaceWidthRatio = item.getDouble("minFaceWidthRatio").toFloat(),
                    maxHeadPoseDegrees = item.getDouble("maxHeadPoseDegrees").toFloat(),
                    minEyeOpenProbability = item.optionalDouble("minEyeOpenProbability")?.toFloat(),
                    headMeasurement = enumValue(item.optString("headMeasurement", "crown_to_chin")),
                    faceCenterXRatio = item.optDouble("faceCenterXRatio", 0.5),
                    faceCenterXTolerance = item.optDouble("faceCenterXTolerance", 0.035),
                    faceCenterConstraint = enumValue(item.optString("faceCenterConstraint", "soft")),
                    shoulderLineRatio = item.optionalDouble("shoulderLineRatio"),
                    shoulderLineTolerance = item.optDouble("shoulderLineTolerance", 0.055),
                    shoulderLineConstraint = enumValue(item.optString("shoulderLineConstraint", "soft")),
                    maxUpscaleRatio = item.optDouble("maxUpscaleRatio", 1.35),
                    portraitBottomRatio = item.optDouble("portraitBottomRatio", 1.0),
                    maxPortraitWidthRatio = item.optDouble("maxPortraitWidthRatio", 1.25),
                    bottomPolicy = enumValue(item.optString("bottomPolicy", "prefer_touch")),
                    strictComposition = item.optBoolean("strictComposition", true),
                )
                check(put(profile.id, profile) == null) { "Duplicate ID photo rule profile: ${profile.id}" }
            }
        }
    }

    private fun selectLocale(context: Context, locale: Locale): String {
        val available = context.assets.list(I18N_DIRECTORY).orEmpty().map { it.removeSuffix(".json") }
        val exact = locale.toLanguageTag()
        return available.firstOrNull { it.equals(exact, true) }
            ?: available.firstOrNull { it.equals(locale.language, true) }
            ?: if (locale.language.equals("zh", true) && "zh-CN" in available) "zh-CN" else FALLBACK_LOCALE
    }

    private fun readLocalization(context: Context, locale: String): String =
        context.assets.readText("$I18N_DIRECTORY/$locale.json")

    private fun parseStrings(json: String): Map<String, String> {
        val values = JSONObject(json).getJSONObject("strings")
        return buildMap { values.keys().forEach { key -> put(key, values.getString(key)) } }
    }

    private fun android.content.res.AssetManager.readText(path: String): String =
        open(path).bufferedReader().use { it.readText() }

    private fun JSONObject.getDecimal(name: String): BigDecimal = BigDecimal(get(name).toString())
    private fun JSONObject.optionalDecimal(name: String): BigDecimal? =
        if (has(name) && !isNull(name)) getDecimal(name) else null
    private fun JSONObject.optionalDouble(name: String): Double? =
        if (has(name) && !isNull(name)) getDouble(name) else null
    private fun JSONObject.optionalLong(name: String): Long? =
        if (has(name) && !isNull(name)) getLong(name) else null
    private fun JSONObject.optionalString(name: String): String? =
        if (has(name) && !isNull(name)) getString(name) else null
    private fun org.json.JSONArray.toStringList(): List<String> =
        buildList(length()) { for (index in 0 until length()) add(getString(index)) }
    private inline fun <reified T : Enum<T>> enumValue(value: String): T =
        enumValueOf(value.uppercase(Locale.ROOT))
}

private data class IdPhotoRuleProfile(
    val id: String,
    val faceHeightRatio: Double,
    val faceTopRatio: Double,
    val faceHeightTolerance: Double,
    val topMarginTolerance: Double,
    val faceHeightConstraint: IdPhotoConstraintLevel,
    val faceTopConstraint: IdPhotoConstraintLevel,
    val eyeLineRatio: Double?,
    val eyeLineTolerance: Double,
    val eyeLineConstraint: IdPhotoConstraintLevel,
    val minFaceWidthRatio: Float,
    val maxHeadPoseDegrees: Float,
    val minEyeOpenProbability: Float?,
    val headMeasurement: IdPhotoHeadMeasurement,
    val faceCenterXRatio: Double,
    val faceCenterXTolerance: Double,
    val faceCenterConstraint: IdPhotoConstraintLevel,
    val shoulderLineRatio: Double?,
    val shoulderLineTolerance: Double,
    val shoulderLineConstraint: IdPhotoConstraintLevel,
    val maxUpscaleRatio: Double,
    val portraitBottomRatio: Double,
    val maxPortraitWidthRatio: Double,
    val bottomPolicy: IdPhotoBottomPolicy,
    val strictComposition: Boolean,
)

private data class ParsedJurisdictionRegistry(
    val listVersion: String,
    val standard: String,
    val source: IdPhotoRegistrySource,
    val jurisdictions: List<IdPhotoJurisdiction>,
)

private fun isValidIsoDate(value: String): Boolean {
    val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
        isLenient = false
    }
    val parsed = runCatching { formatter.parse(value) }.getOrNull() ?: return false
    return formatter.format(parsed) == value
}
