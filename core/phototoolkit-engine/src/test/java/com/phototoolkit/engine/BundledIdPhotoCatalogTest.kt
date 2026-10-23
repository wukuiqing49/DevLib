package com.phototoolkit.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BundledIdPhotoCatalogTest {

    @Test
    fun officialSourceFreshnessExpiresAfterProductReviewWindow() {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val source = IdPhotoSource(
            id = "official.test",
            authority = "Authority",
            title = "Rule",
            url = "https://example.gov/rule",
            verifiedAt = "2026-01-01",
        )

        val utc = TimeZone.getTimeZone("UTC")
        assertTrue(source.isFresh(checkNotNull(format.parse("2026-06-30")).time, timeZone = utc))
        assertFalse(source.isFresh(checkNotNull(format.parse("2026-07-01")).time, timeZone = utc))
        assertFalse(source.copy(verifiedAt = null).isFresh(checkNotNull(format.parse("2026-01-01")).time, timeZone = utc))
        assertFalse(source.copy(verifiedAt = "2026-01-01-invalid").isFresh(
            checkNotNull(format.parse("2026-01-01")).time,
            timeZone = utc,
        ))
    }

    @Test
    fun officialVariantGenerationRequiresCurrentSourcesAndEffectiveDates() {
        val catalog = IdPhotoCatalogRepository.load(RuntimeEnvironment.getApplication(), Locale.ENGLISH)
        val uk = catalog.documents.flatMap { it.variants }.single { it.id == "gb_passport_print" }
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        assertTrue(
            uk.isCurrentForGeneration(
                asOfEpochMillis = checkNotNull(format.parse("2026-08-10")).time,
                timeZone = TimeZone.getTimeZone("UTC"),
            ),
        )
        assertFalse(
            uk.isCurrentForGeneration(
                asOfEpochMillis = checkNotNull(format.parse("2027-02-01")).time,
                timeZone = TimeZone.getTimeZone("UTC"),
            ),
        )
        assertFalse(
            uk.copy(effectiveTo = "2026-08-01").isCurrentForGeneration(
                asOfEpochMillis = checkNotNull(format.parse("2026-08-10")).time,
                timeZone = TimeZone.getTimeZone("UTC"),
            ),
        )
    }

    @Test
    fun bundledCatalogSeparatesVerifiedRulesFromReferencePresets() {
        val catalog = IdPhotoCatalogRepository.load(RuntimeEnvironment.getApplication(), Locale.ENGLISH)
        val variants = catalog.documents.flatMap { it.variants }
        val commonPrint = variants.single { it.id == "common_print_25x35_print" }
        val schengenReference = variants.single { it.id == "schengen_visa_print_reported" }
        val ukDigital = variants.single { it.id == "gb_passport_digital" }
        val ukPrint = variants.single { it.id == "gb_passport_print" }
        val usPrint = variants.single { it.id == "us_passport_print" }
        val canadaDigital = variants.single { it.id == "canada_passport_digital" }
        val canadaPrint = variants.single { it.id == "canada_passport_print" }
        val koreaDigital = variants.single { it.id == "korea_passport_digital" }
        val koreaPrint = variants.single { it.id == "korea_passport_print" }
        val australiaPrint = variants.single { it.id == "australia_passport_print" }
        val singaporeDigital = variants.single { it.id == "singapore_passport_digital" }
        val hongKongDigital = variants.single { it.id == "hong_kong_passport_digital_camera" }
        val hongKongPrint = variants.single { it.id == "hong_kong_passport_print" }
        val francePrint = variants.single { it.id == "france_passport_print" }
        val franceNationalId = variants.single { it.id == "france_national_id_print" }

        assertEquals(5, catalog.schemaVersion)
        assertEquals(13, catalog.catalogVersion)
        assertEquals(6, catalog.documents.count { it.documentType == IdPhotoDocumentType.GENERAL })
        assertEquals(20, catalog.documents.count { it.documentType == IdPhotoDocumentType.PASSPORT })
        assertEquals(1, catalog.documents.count { it.documentType == IdPhotoDocumentType.VISA })
        assertEquals(1, catalog.documents.count { it.documentType == IdPhotoDocumentType.NATIONAL_ID })
        assertEquals(0, catalog.documents.count { it.documentType == IdPhotoDocumentType.DRIVER_LICENSE })
        assertEquals(13, variants.count { it.faceHeightConstraint == IdPhotoConstraintLevel.HARD })
        assertTrue(variants.all { it.faceTopConstraint == IdPhotoConstraintLevel.SOFT })
        assertTrue(variants.all { it.eyeLineConstraint == IdPhotoConstraintLevel.SOFT })
        assertTrue(variants.all { it.faceCenterConstraint == IdPhotoConstraintLevel.SOFT })
        assertTrue(variants.all { it.shoulderLineConstraint == IdPhotoConstraintLevel.SOFT })
        assertTrue(variants.all { it.headMeasurement == IdPhotoHeadMeasurement.CROWN_TO_CHIN })
        assertTrue(variants.all { it.maxUpscaleRatio in 1.0..1.35 })

        variants.forEach { variant ->
            val spec = variant.toSpec()
            val head = PortraitHeadGeometry(
                left = 300.0,
                top = 100.0,
                width = 300.0,
                height = 400.0,
                eyeCenterY = 320.0,
            )
            val plan = IdPhotoComplianceEvaluator.plan(spec, head)
            assertEquals("Unexpected head height for ${variant.id}", spec.faceHeightRatio, plan.faceHeightRatio, 0.000001)
            assertTrue(
                "Unexpected head top for ${variant.id}",
                plan.faceTopRatio in spec.faceTopMinRatio..spec.faceTopMaxRatio,
            )
            if (variant.topPolicy == IdPhotoTopPolicy.FIXED_REFERENCE && spec.eyeLineRatio == null) {
                assertEquals("Unexpected fixed head top for ${variant.id}", spec.faceTopRatio, plan.faceTopRatio, 0.000001)
            }
        }

        variants.filter { it.delivery == IdPhotoDelivery.PRINT }.forEach { variant ->
            val spec = variant.toSpec()
            val layout = IdPhotoPrintSheetPlanner.planFourBySix(spec.widthPx, spec.heightPx)
            assertTrue("Print preset does not fit 4 x 6 sheet: ${variant.id}", layout != null)
        }

        assertEquals(0.68, commonPrint.faceHeightRatio, 0.000001)
        assertEquals(0.64, commonPrint.faceHeightMinRatio, 0.000001)
        assertEquals(0.72, commonPrint.faceHeightMaxRatio, 0.000001)
        assertEquals(0.0, commonPrint.faceTopRatio, 0.000001)
        assertEquals(1.0, commonPrint.maxPortraitWidthRatio, 0.000001)
        assertEquals(IdPhotoBottomPolicy.PRESERVE_COMPOSITION, commonPrint.bottomPolicy)
        assertEquals(null, commonPrint.eyeLineRatio)
        assertEquals(0.68, IdPhotoSpec.STANDARD_413_X_591.faceHeightRatio, 0.000001)
        assertEquals(0.0, IdPhotoSpec.STANDARD_413_X_591.faceTopRatio, 0.000001)
        assertEquals(IdPhotoTopPolicy.AUTO_COMPOSITION, IdPhotoSpec.STANDARD_413_X_591.topPolicy)
        assertEquals(0.755556, schengenReference.faceHeightRatio, 0.000001)
        assertEquals(0.0, schengenReference.faceTopRatio, 0.000001)


        assertEquals(IdPhotoDelivery.DIGITAL, ukDigital.delivery)
        assertEquals(IdPhotoVerificationStatus.VERIFIED, ukDigital.verificationStatus)
        assertEquals(IdPhotoEditPolicy.PROHIBITED, ukDigital.editPolicy)
        assertEquals(600, ukDigital.toSpec().widthPx)
        assertEquals(750, ukDigital.toSpec().heightPx)
        assertEquals(IdPhotoVerificationStatus.VERIFIED, ukPrint.verificationStatus)
        assertEquals(IdPhotoEditPolicy.PROHIBITED, ukPrint.editPolicy)
        assertEquals(0.7, ukDigital.faceHeightRatio, 0.000001)
        assertEquals(0.644444, ukDigital.faceHeightMinRatio, 0.000001)
        assertEquals(0.755556, ukDigital.faceHeightMaxRatio, 0.000001)
        assertEquals(2, ukPrint.sources.size)
        assertEquals(0.644444, ukPrint.faceHeightMinRatio, 0.000001)
        assertEquals(0.755556, ukPrint.faceHeightMaxRatio, 0.000001)

        assertEquals(IdPhotoVerificationStatus.VERIFIED, usPrint.verificationStatus)
        assertEquals(IdPhotoEditPolicy.RESTRICTED, usPrint.editPolicy)
        assertEquals(600, usPrint.toSpec().widthPx)
        assertEquals(600, usPrint.toSpec().heightPx)
        assertEquals(0.59375, usPrint.faceHeightRatio, 0.000001)
        assertEquals(0.5, usPrint.faceHeightMinRatio, 0.000001)
        assertEquals(0.6875, usPrint.faceHeightMaxRatio, 0.000001)
        assertEquals(IdPhotoBottomPolicy.PRESERVE_COMPOSITION, usPrint.bottomPolicy)
        assertTrue(IdPhotoApplicationChannel.PHOTO_TOOL in usPrint.applicationChannels)

        assertEquals(IdPhotoVerificationStatus.VERIFIED, canadaDigital.verificationStatus)
        assertEquals(IdPhotoDimensionMode.RANGE, canadaDigital.output.dimensionMode)
        assertEquals(1200, canadaDigital.toSpec().widthPx)
        assertEquals(1800, canadaDigital.toSpec().heightPx)
        assertEquals(200_000L, canadaDigital.output.minBytes)
        assertEquals(5_000_000L, canadaDigital.output.maxBytes)
        assertEquals(IdPhotoCapturePolicy.COMMERCIAL_PHOTOGRAPHER_REQUIRED, canadaDigital.capturePolicy)
        assertEquals(
            setOf(IdPhotoSourceImagePolicy.ORIGINAL_CAMERA_FILE_REQUIRED),
            canadaDigital.sourceImagePolicies,
        )
        assertEquals(IdPhotoVerificationStatus.VERIFIED, canadaPrint.verificationStatus)
        assertEquals(IdPhotoEditPolicy.PROHIBITED, canadaPrint.editPolicy)
        assertEquals(591, canadaPrint.toSpec().widthPx)
        assertEquals(827, canadaPrint.toSpec().heightPx)
        assertEquals(0.442857, canadaPrint.faceHeightMinRatio, 0.000001)
        assertEquals(0.514286, canadaPrint.faceHeightMaxRatio, 0.000001)

        assertEquals(IdPhotoVerificationStatus.VERIFIED, koreaDigital.verificationStatus)
        assertEquals(IdPhotoDimensionMode.RANGE, koreaDigital.output.dimensionMode)
        assertEquals(395, koreaDigital.output.minWidth?.toInt())
        assertEquals(550, koreaDigital.output.maxHeight?.toInt())
        assertEquals(500_000L, koreaDigital.output.maxBytes)
        assertEquals(IdPhotoEditPolicy.RESTRICTED, koreaDigital.editPolicy)
        assertEquals(IdPhotoBottomPolicy.PRESERVE_COMPOSITION, koreaDigital.bottomPolicy)
        assertEquals(IdPhotoVerificationStatus.VERIFIED, koreaPrint.verificationStatus)
        assertEquals(IdPhotoEditPolicy.RESTRICTED, koreaPrint.editPolicy)
        assertEquals(IdPhotoBottomPolicy.PRESERVE_COMPOSITION, koreaPrint.bottomPolicy)

        assertEquals(IdPhotoVerificationStatus.VERIFIED, australiaPrint.verificationStatus)
        assertEquals(IdPhotoDimensionMode.RANGE, australiaPrint.output.dimensionMode)
        assertEquals(2, australiaPrint.sources.size)
        assertEquals(35, australiaPrint.output.minWidth?.toInt())
        assertEquals(50, australiaPrint.output.maxHeight?.toInt())
        assertEquals(IdPhotoBottomPolicy.PRESERVE_COMPOSITION, australiaPrint.bottomPolicy)

        assertEquals(IdPhotoVerificationStatus.CHANNEL_VERIFIED, singaporeDigital.verificationStatus)
        assertEquals(setOf(IdPhotoOutputFormat.JPEG, IdPhotoOutputFormat.PNG), singaporeDigital.output.formats)
        assertEquals(8_000_000L, singaporeDigital.output.maxBytes)
        assertTrue(IdPhotoApplicationChannel.MOBILE_APP in singaporeDigital.applicationChannels)
        assertEquals(IdPhotoBottomPolicy.PRESERVE_COMPOSITION, singaporeDigital.bottomPolicy)

        assertEquals(IdPhotoVerificationStatus.VERIFIED, hongKongDigital.verificationStatus)
        assertEquals(IdPhotoDimensionMode.MINIMUM, hongKongDigital.output.dimensionMode)
        assertEquals(1200, hongKongDigital.toSpec().widthPx)
        assertEquals(1600, hongKongDigital.toSpec().heightPx)
        assertEquals(5_000_000L, hongKongDigital.output.maxBytes)
        assertEquals(IdPhotoVerificationStatus.VERIFIED, hongKongPrint.verificationStatus)
        assertEquals(472, hongKongPrint.toSpec().widthPx)
        assertEquals(591, hongKongPrint.toSpec().heightPx)
        assertEquals(IdPhotoBottomPolicy.PRESERVE_COMPOSITION, hongKongDigital.bottomPolicy)
        assertEquals(IdPhotoBottomPolicy.PRESERVE_COMPOSITION, hongKongPrint.bottomPolicy)

        val verifiedGeneratable = variants.filter {
            (it.verificationStatus == IdPhotoVerificationStatus.VERIFIED ||
                it.verificationStatus == IdPhotoVerificationStatus.CHANNEL_VERIFIED) &&
                it.editPolicy != IdPhotoEditPolicy.PROHIBITED
        }
        assertTrue(verifiedGeneratable.isNotEmpty())
        assertTrue(verifiedGeneratable.all {
            it.bottomPolicy == IdPhotoBottomPolicy.PRESERVE_COMPOSITION
        })

        assertEquals(IdPhotoVerificationStatus.VERIFIED, francePrint.verificationStatus)
        assertEquals(IdPhotoEditPolicy.PROHIBITED, francePrint.editPolicy)
        assertEquals(413, francePrint.toSpec().widthPx)
        assertEquals(531, francePrint.toSpec().heightPx)
        assertEquals(0.711111, francePrint.faceHeightMinRatio, 0.000001)
        assertEquals(0.8, francePrint.faceHeightMaxRatio, 0.000001)
        assertEquals(IdPhotoVerificationStatus.VERIFIED, franceNationalId.verificationStatus)
        assertEquals(IdPhotoDocumentType.NATIONAL_ID, catalog.documents.single {
            it.id == franceNationalId.documentId
        }.documentType)
        assertEquals(IdPhotoEditPolicy.PROHIBITED, franceNationalId.editPolicy)
        assertEquals(413, franceNationalId.toSpec().widthPx)
        assertEquals(531, franceNationalId.toSpec().heightPx)
        assertEquals(13, variants.count { it.verificationStatus == IdPhotoVerificationStatus.VERIFIED })
        assertTrue(variants.all { it.ruleVersion.isNotBlank() })
        assertTrue(variants.all { it.reviewedAt.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$")) })
        assertEquals(1, variants.count { it.verificationStatus == IdPhotoVerificationStatus.CHANNEL_VERIFIED })
        assertTrue(variants.filter { it.verificationStatus == IdPhotoVerificationStatus.VERIFIED }
            .flatMap { it.sources }
            .all { !it.url.isNullOrBlank() && !it.verifiedAt.isNullOrBlank() })
        assertTrue(variants.filter {
            it.verificationStatus == IdPhotoVerificationStatus.VERIFIED ||
                it.verificationStatus == IdPhotoVerificationStatus.CHANNEL_VERIFIED
        }.all { it.requirements.isNotEmpty() })

        val officialReported = setOf(
            "china_passport_print_reported",
            "japan_passport_print_reported",
            "new_zealand_passport_digital_reported",
            "new_zealand_passport_print_reported",
            "schengen_visa_print_reported",
            "thailand_passport_print_reported",
            "vietnam_passport_print_reported",
            "taiwan_passport_print_reported",
            "uae_passport_print_reported",
        )
        assertTrue(variants.filter { it.id in officialReported }
            .all { it.verificationStatus == IdPhotoVerificationStatus.OFFICIAL_REPORTED })
        assertTrue(variants.filter { it.id in officialReported }
            .all {
                it.faceHeightConstraint == IdPhotoConstraintLevel.SOFT &&
                    it.faceTopConstraint == IdPhotoConstraintLevel.SOFT
            })
        val newZealandDigital = variants.single { it.id == "new_zealand_passport_digital_reported" }
        assertEquals(IdPhotoDelivery.DIGITAL, newZealandDigital.delivery)
        assertEquals(IdPhotoDimensionMode.RANGE, newZealandDigital.output.dimensionMode)
        assertEquals(900, newZealandDigital.output.minWidth?.toInt())
        assertEquals(4500, newZealandDigital.output.maxWidth?.toInt())
        assertEquals(250_000L, newZealandDigital.output.minBytes)
        assertEquals(5_000_000L, newZealandDigital.output.maxBytes)
        assertEquals(0.711111, variants.single { it.id == "japan_passport_print_reported" }.faceHeightMinRatio, 0.000001)
        assertEquals(0.133333, variants.single { it.id == "japan_passport_print_reported" }.faceTopMaxRatio, 0.000001)
        assertEquals(0.0, variants.single { it.id == "china_passport_print_reported" }.faceTopMinRatio, 0.000001)
        assertEquals(
            IdPhotoTopPolicy.AUTO_COMPOSITION,
            variants.single { it.id == "china_passport_print_reported" }.topPolicy,
        )
        assertEquals(
            IdPhotoTopPolicy.FIXED_REFERENCE,
            variants.single { it.id == "japan_passport_print_reported" }.topPolicy,
        )
        assertEquals(
            IdPhotoTopPolicy.AUTO_COMPOSITION,
            variants.single { it.id == "germany_passport_print" }.topPolicy,
        )
        assertEquals(IdPhotoVerificationStatus.VERIFIED, variants.single { it.id == "germany_passport_print" }.verificationStatus)
        assertEquals(0.711111, variants.single { it.id == "germany_passport_print" }.faceHeightMinRatio, 0.000001)
        assertEquals(0.65, variants.single { it.id == "vietnam_passport_print_reported" }.faceHeightRatio, 0.000001)
        assertEquals(0.044444, variants.single { it.id == "thailand_passport_print_reported" }.faceTopMinRatio, 0.000001)

        val stillUnverified = setOf(
            "india_passport_print_reference",
            "malaysia_passport_print_reference",
            "indonesia_passport_print_reference",
            "philippines_passport_print_reference",
        )
        assertTrue(variants.filter { it.id in stillUnverified }
            .all { it.verificationStatus == IdPhotoVerificationStatus.REFERENCE })
        assertFalse(variants.any { it.id == "us_passport_print_reference" })
        assertEquals(
            IdPhotoCoverageLevel.REFERENCE,
            catalog.coverage().single {
                it.jurisdiction.code == "JP" && it.documentType == IdPhotoDocumentType.PASSPORT
            }.level,
        )
    }

    @Test
    fun bundledJurisdictionRegistryExposesGlobalCoverageWithoutInventingRules() {
        val catalog = IdPhotoCatalogRepository.load(RuntimeEnvironment.getApplication(), Locale.ENGLISH)
        val countries = catalog.jurisdictions.filter {
            it.kind == IdPhotoJurisdictionKind.COUNTRY_OR_AREA
        }
        val coverage = catalog.coverage()

        assertEquals("2026-08-06", catalog.jurisdictionListVersion)
        assertEquals("ISO 3166-1 alpha-2", catalog.jurisdictionStandard)
        assertEquals(
            "International Organization for Standardization",
            catalog.jurisdictionSource?.authority,
        )
        assertEquals("2026-08-06", catalog.jurisdictionSource?.reviewedAt)
        assertEquals(249, countries.size)
        assertEquals(249, countries.map { it.code }.distinct().size)
        assertEquals(29, catalog.jurisdictions.single { it.code == "SCHENGEN" }.memberCodes.size)
        assertEquals(750, coverage.size)
        assertFalse(coverage.any { it.jurisdiction.kind == IdPhotoJurisdictionKind.PRESET })
        assertEquals(
            IdPhotoCoverageLevel.VERIFIED,
            coverage.single {
                it.jurisdiction.code == "FR" &&
                    it.documentType == IdPhotoDocumentType.NATIONAL_ID
            }.level,
        )
        assertEquals(
            IdPhotoCoverageLevel.REFERENCE,
            coverage.single {
                it.jurisdiction.code == "SCHENGEN" &&
                    it.documentType == IdPhotoDocumentType.VISA
            }.level,
        )
        assertEquals(
            IdPhotoCoverageLevel.MISSING,
            coverage.single {
                it.jurisdiction.code == "US" &&
                    it.documentType == IdPhotoDocumentType.NATIONAL_ID
            }.level,
        )
    }

    @Test
    fun bundledCatalogLocalizesCommercialRequirements() {
        val catalog = IdPhotoCatalogRepository.load(RuntimeEnvironment.getApplication(), Locale.SIMPLIFIED_CHINESE)
        val variants = catalog.documents.flatMap { it.variants }

        assertTrue(variants.single { it.id == "canada_passport_digital" }
            .requirements.single().contains("相机原始文件"))
        assertTrue(variants.single { it.id == "hong_kong_passport_digital_camera" }
            .requirements.single().contains("1200 x 1600 px"))
        assertTrue(variants.single { it.id == "singapore_passport_digital" }
            .requirements.single().contains("推荐尺寸"))
        assertEquals(
            "国民身份证照片",
            catalog.documents.single { it.id == "france_national_id" }.displayName,
        )
    }
}
