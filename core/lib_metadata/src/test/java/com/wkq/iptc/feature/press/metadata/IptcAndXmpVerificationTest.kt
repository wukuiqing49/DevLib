package com.wkq.iptc.feature.press.metadata

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.iptc.IptcDirectory
import com.wkq.dev.metadata.model.MetadataFieldDefinitions
import com.wkq.dev.metadata.model.MetadataTemplate
import org.apache.commons.imaging.bytesource.ByteSource
import org.apache.commons.imaging.formats.jpeg.JpegImagingParameters
import org.apache.commons.imaging.formats.jpeg.iptc.IptcTypes
import org.apache.commons.imaging.formats.jpeg.iptc.JpegIptcRewriter
import org.apache.commons.imaging.formats.jpeg.iptc.PhotoshopApp13Data
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

class IptcAndXmpVerificationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testFile: File

    // 最简合法的 JPEG 字节数组 (包含 SOI, APP0, JFIF 标志以及 EOI)
    private val minimalJpegBytes = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), // SOI
        0xFF.toByte(), 0xE0.toByte(), 0x00.toByte(), 0x10.toByte(), // APP0
        0x4A.toByte(), 0x46.toByte(), 0x49.toByte(), 0x46.toByte(), 0x00.toByte(), // JFIF
        0x01.toByte(), 0x01.toByte(), 0x01.toByte(), 0x00.toByte(), 0x60.toByte(), 0x00.toByte(), 0x60.toByte(), 0x00.toByte(), 0x00.toByte(),
        0xFF.toByte(), 0xD9.toByte()  // EOI
    )

    @Before
    fun setUp() {
        testFile = tempFolder.newFile("test_write.jpg")
        testFile.writeBytes(minimalJpegBytes)
    }

    /**
     * 1. 验证“不选择模板，只填快捷 IPTC”组合。
     * 预期：只有快捷填写的字段写入，其他未填字段为空，不凭空写默认脏数据。
     */
    @Test
    fun testWriteOnlyQuickMetadata() {
        val quickTemplate = MetadataTemplate(
            id = "quick_test",
            displayName = "Quick IPTC",
            headline = "突发新闻现场",
            caption = "救援人员正在展开搜救",
            keywords = listOf("突发", "救援"),
            creator = "张记者",
            iptcAuthor = "张记者",
            enabledFields = listOf("headline", "caption", "keywords", "creator", "iptcAuthor")
        )

        // 1. 验证 IPTC 写入与读取 (纯 Java)
        IptcWriter.writeTemplateMetadata(testFile, quickTemplate).getOrThrow()

        val iptc = readIptc(testFile)
        assertNotNull(iptc)
        assertEquals("突发新闻现场", iptc!!.getString(IptcDirectory.TAG_HEADLINE))
        assertEquals("救援人员正在展开搜救", iptc.getString(IptcDirectory.TAG_CAPTION))
        assertArrayEquals(arrayOf("突发", "救援"), iptc.getStringArray(IptcDirectory.TAG_KEYWORDS))
        assertEquals("张记者", iptc.getString(IptcDirectory.TAG_BY_LINE))
        assertNull(iptc.getString(IptcDirectory.TAG_OBJECT_NAME))

        // 验证未填字段在 IPTC 中应全为空，没有默认脏数据（例如城市和国家没有被默认写入）
        assertNull(iptc.getString(IptcDirectory.TAG_CITY))
        assertNull(iptc.getString(IptcDirectory.TAG_COUNTRY_OR_PRIMARY_LOCATION_NAME))

        // 2. 验证 XMP 包生成 (纯 Java 内存校验)
        val xmpXml = XmpWriter.buildTemplatePacket(quickTemplate)
        val doc = parseXml(xmpXml)
        
        assertEquals("突发新闻现场", doc.firstText("photoshop:Headline"))
        assertEquals("救援人员正在展开搜救", doc.firstText("dc:description"))
        assertEquals("张记者", doc.firstText("dc:creator"))
        assertEquals("", doc.firstText("photoshop:City")) // 应该为空
    }

    /**
     * 2. 验证“选择模板，不填快捷字段”组合。
     * 预期：先用模板字段。
     */
    @Test
    fun testWriteOnlyTemplate() {
        val template = MetadataTemplate(
            id = "news_template",
            displayName = "新闻通用模板",
            copyright = "XX通讯社 版权所有",
            credit = "XX通讯社",
            source = "社报部",
            iptcAuthor = "李记者",
            location = "二七广场",
            city = "郑州",
            country = "中国",
            isoCountryCode = "CN",
            enabledFields = listOf(
                "copyright",
                "credit",
                "source",
                "iptcAuthor",
                "location",
                "city",
                "country",
                "isoCountryCode"
            )
        )

        // 1. 验证 IPTC 写入
        IptcWriter.writeTemplateMetadata(testFile, template).getOrThrow()

        val iptc = readIptc(testFile)
        assertNotNull(iptc)
        assertEquals("XX通讯社 版权所有", iptc!!.getString(IptcDirectory.TAG_COPYRIGHT_NOTICE))
        assertEquals("XX通讯社", iptc.getString(IptcDirectory.TAG_CREDIT))
        assertEquals("社报部", iptc.getString(IptcDirectory.TAG_SOURCE))
        assertEquals("李记者", iptc.getString(IptcDirectory.TAG_BY_LINE))
        assertTrue(hasIptcRecord(testFile, IptcTypes.SUBLOCATION, "二七广场"))
        assertEquals("郑州", iptc.getString(IptcDirectory.TAG_CITY))
        assertEquals("中国", iptc.getString(IptcDirectory.TAG_COUNTRY_OR_PRIMARY_LOCATION_NAME))
        assertEquals("CN", iptc.getString(IptcDirectory.TAG_COUNTRY_OR_PRIMARY_LOCATION_CODE))

        // 模板未填的 Headline/Caption 在 IPTC 中应该为空
        assertNull(iptc.getString(IptcDirectory.TAG_HEADLINE))
        assertNull(iptc.getString(IptcDirectory.TAG_CAPTION))

        // 2. 验证 XMP 包生成
        val xmpXml = XmpWriter.buildTemplatePacket(template)
        val doc = parseXml(xmpXml)
        
        assertEquals("XX通讯社 版权所有", doc.firstText("dc:rights"))
        assertEquals("XX通讯社", doc.firstText("photoshop:Credit"))
        assertEquals("社报部", doc.firstText("photoshop:Source"))
        assertEquals("李记者", doc.firstText("dc:creator"))
        assertEquals("二七广场", doc.firstText("Iptc4xmpCore:Location"))
        assertEquals("二七广场", doc.firstText("Iptc4xmpExt:Sublocation"))
        assertEquals("郑州", doc.firstText("photoshop:City"))
        assertEquals("郑州", doc.firstText("Iptc4xmpExt:City"))
        assertEquals("中国", doc.firstText("photoshop:Country"))
        assertEquals("中国", doc.firstText("Iptc4xmpExt:CountryName"))
        assertEquals("CN", doc.firstText("Iptc4xmpCore:CountryCode"))
        assertEquals("CN", doc.firstText("Iptc4xmpExt:CountryCode"))
        assertEquals("", doc.firstText("photoshop:Headline"))
    }

    /**
     * 3. 验证“选择模板，同时填快捷字段覆盖模板字段”组合。
     * 预期：快捷字段不为空时，覆盖模板同名字段；快捷字段为空时，不应该清掉模板字段。
     */
    @Test
    fun testWriteTemplateWithQuickOverrides() {
        val baseTemplate = MetadataTemplate(
            id = "sports_template",
            displayName = "体育预设",
            headline = "体育赛事",
            caption = "精彩瞬间记录",
            keywords = listOf("体育", "赛事"),
            credit = "新华社",
            source = "摄影部",
            copyright = "Copyright 2026",
            city = "杭州",
            country = "中国",
            isoCountryCode = "CN",
            enabledFields = listOf("headline", "caption", "keywords", "credit", "source", "copyright", "city", "country", "isoCountryCode")
        )

        // 模拟快捷字段输入：用户覆盖了 Headline、Caption 和 Keywords，但 credit/source/copyright/city 等保持空。
        val quickTemplate = MetadataTemplate(
            id = "quick_override",
            displayName = "Quick Input",
            headline = "羽毛球男单决赛",
            caption = "选手在比赛中腾空扣杀",
            keywords = listOf("羽毛球", "决赛", "扣杀"),
            enabledFields = listOf("headline", "caption", "keywords")
        )

        // 模拟合并
        val mergedTemplate = baseTemplate.copy(
            headline = quickTemplate.headline.ifBlank { baseTemplate.headline },
            caption = quickTemplate.caption.ifBlank { baseTemplate.caption },
            keywords = quickTemplate.keywords.ifEmpty { baseTemplate.keywords },
            credit = quickTemplate.credit.ifBlank { baseTemplate.credit },
            source = quickTemplate.source.ifBlank { baseTemplate.source },
            copyright = quickTemplate.copyright.ifBlank { baseTemplate.copyright },
            city = quickTemplate.city.ifBlank { baseTemplate.city },
            country = quickTemplate.country.ifBlank { baseTemplate.country },
            isoCountryCode = quickTemplate.isoCountryCode.ifBlank { baseTemplate.isoCountryCode },
            enabledFields = (baseTemplate.enabledFields + quickTemplate.enabledFields).distinct()
        )

        // 1. 验证 IPTC 写入
        IptcWriter.writeTemplateMetadata(testFile, mergedTemplate).getOrThrow()

        val iptc = readIptc(testFile)
        assertNotNull(iptc)
        // 被快捷覆盖了的字段，显示快捷的内容
        assertEquals("羽毛球男单决赛", iptc!!.getString(IptcDirectory.TAG_HEADLINE))
        assertEquals("选手在比赛中腾空扣杀", iptc.getString(IptcDirectory.TAG_CAPTION))
        assertArrayEquals(arrayOf("羽毛球", "决赛", "扣杀"), iptc.getStringArray(IptcDirectory.TAG_KEYWORDS))
        
        // 快捷没有填写的字段，保持模板内容不被清空
        assertEquals("新华社", iptc.getString(IptcDirectory.TAG_CREDIT))
        assertEquals("摄影部", iptc.getString(IptcDirectory.TAG_SOURCE))
        assertEquals("Copyright 2026", iptc.getString(IptcDirectory.TAG_COPYRIGHT_NOTICE))
        assertEquals("杭州", iptc.getString(IptcDirectory.TAG_CITY))
        assertEquals("中国", iptc.getString(IptcDirectory.TAG_COUNTRY_OR_PRIMARY_LOCATION_NAME))

        // 2. 验证 XMP 包生成
        val xmpXml = XmpWriter.buildTemplatePacket(mergedTemplate)
        val doc = parseXml(xmpXml)
        
        assertEquals("羽毛球男单决赛", doc.firstText("photoshop:Headline"))
        assertEquals("选手在比赛中腾空扣杀", doc.firstText("dc:description"))
        assertEquals("新华社", doc.firstText("photoshop:Credit"))
        assertEquals("摄影部", doc.firstText("photoshop:Source"))
        assertEquals("Copyright 2026", doc.firstText("dc:rights"))
        assertEquals("杭州", doc.firstText("photoshop:City"))
    }

    /**
     * 4. 验证清理元数据：物理清除 IPTC (APP13 段) 并在测试中彻底清除干净。
     */
    @Test
    fun testMetadataScrubbing() {
        val template = MetadataTemplate(
            id = "scrub_test_temp",
            displayName = "测试模板",
            headline = "需要被擦除的标题",
            caption = "需要被擦除的内容",
            keywords = listOf("擦除"),
            copyright = "版权信息",
            city = "杭州",
            enabledFields = listOf("headline", "caption", "keywords", "copyright", "city")
        )

        // 写入 IPTC
        IptcWriter.writeTemplateMetadata(testFile, template).getOrThrow()
        
        // 校验写入成功
        val beforeIptc = readIptc(testFile)
        assertNotNull(beforeIptc)
        assertEquals("需要被擦除的标题", beforeIptc!!.getString(IptcDirectory.TAG_HEADLINE))

        // 执行 IPTC 物理清理 (使用 MetadataScrubber 内部的重写逻辑)
        val byteSource = ByteSource.file(testFile)
        val metadata = org.apache.commons.imaging.formats.jpeg.JpegImageParser()
            .getPhotoshopMetadata(byteSource, JpegImagingParameters())
        val nonIptcBlocks = metadata?.photoshopApp13Data?.nonIptcBlocks.orEmpty()
        val emptyApp13Data = PhotoshopApp13Data(emptyList(), nonIptcBlocks)

        val tempFile = tempFolder.newFile("test_scrubbed.jpg")
        tempFile.outputStream().buffered().use { output ->
            JpegIptcRewriter().writeIptc(byteSource, output, emptyApp13Data)
        }

        // 校验物理清洗后的照片，核心内容字段完全被清空
        val afterIptc = readIptc(tempFile)
        if (afterIptc != null) {
            assertNull("Headline 应该被清空", afterIptc.getString(IptcDirectory.TAG_HEADLINE))
            assertNull("Caption 应该被清空", afterIptc.getString(IptcDirectory.TAG_CAPTION))
            assertNull("Copyright 应该被清空", afterIptc.getString(IptcDirectory.TAG_COPYRIGHT_NOTICE))
            assertNull("City 应该被清空", afterIptc.getString(IptcDirectory.TAG_CITY))
        }
    }

    @Test
    fun testSingleValueFieldsAreNotDuplicated() {
        val template = MetadataTemplate(
            id = "deduplicate_test",
            iptcAuthor = "Reporter",
            writer = "Editor",
            email = "reporter@example.com",
            keywords = listOf("sports", "world"),
            standardFields = mapOf(
                MetadataFieldDefinitions.IIM_BYLINE to "Reporter",
                MetadataFieldDefinitions.IIM_WRITER to "Editor",
                MetadataFieldDefinitions.IIM_CONTACT to
                    "reporter@example.com / 13800000000",
                MetadataFieldDefinitions.IIM_KEYWORDS to "sports,news"
            ),
            enabledFields = listOf(
                "iptcAuthor",
                "writer",
                "email",
                "keywords",
                MetadataFieldDefinitions.IIM_BYLINE,
                MetadataFieldDefinitions.IIM_WRITER,
                MetadataFieldDefinitions.IIM_CONTACT,
                MetadataFieldDefinitions.IIM_KEYWORDS
            )
        )

        IptcWriter.writeTemplateMetadata(testFile, template).getOrThrow()

        val records = org.apache.commons.imaging.formats.jpeg.JpegImageParser()
            .getPhotoshopMetadata(ByteSource.file(testFile), JpegImagingParameters())
            ?.photoshopApp13Data
            ?.records
            .orEmpty()
        assertEquals(1, records.count { it.iptcType == IptcTypes.BYLINE })
        assertEquals(1, records.count { it.iptcType == IptcTypes.WRITER_EDITOR })
        assertEquals(1, records.count { it.iptcType == IptcTypes.CONTACT })
        assertEquals(
            "reporter@example.com / 13800000000",
            records.single { it.iptcType == IptcTypes.CONTACT }.value
        )
        assertEquals(
            listOf("sports", "world", "news"),
            records.filter { it.iptcType == IptcTypes.KEYWORDS }.map { it.value }
        )
    }

    @Test
    fun testUnmodifiedIptcFieldsArePreserved() {
        IptcWriter.writeTemplateMetadata(
            testFile,
            MetadataTemplate(
                id = "first",
                headline = "Existing headline",
                source = "Existing agency",
                enabledFields = listOf("headline", "source")
            )
        ).getOrThrow()

        IptcWriter.writeTemplateMetadata(
            testFile,
            MetadataTemplate(
                id = "second",
                caption = "New caption",
                enabledFields = listOf("caption")
            )
        ).getOrThrow()

        val iptc = requireNotNull(readIptc(testFile))
        assertEquals("Existing headline", iptc.getString(IptcDirectory.TAG_HEADLINE))
        assertEquals("Existing agency", iptc.getString(IptcDirectory.TAG_SOURCE))
        assertEquals("New caption", iptc.getString(IptcDirectory.TAG_CAPTION))
    }

    @Test
    fun testReplaceManagedFieldsRemovesLegacyValues() {
        IptcWriter.writeTemplateMetadata(
            testFile,
            MetadataTemplate(
                id = "legacy",
                headline = "Old headline",
                keywords = listOf("快捷", "old"),
                standardFields = mapOf(
                    MetadataFieldDefinitions.IIM_DATE_CREATED to "20240102"
                ),
                enabledFields = listOf(
                    "headline",
                    "keywords",
                    MetadataFieldDefinitions.IIM_DATE_CREATED
                )
            )
        ).getOrThrow()

        IptcWriter.writeTemplateMetadata(
            photoFile = testFile,
            template = MetadataTemplate(
                id = "replacement",
                caption = "Fresh caption",
                enabledFields = listOf("caption")
            ),
            replaceManagedFields = true
        ).getOrThrow()

        val iptc = requireNotNull(readIptc(testFile))
        assertNull(iptc.getString(IptcDirectory.TAG_HEADLINE))
        assertNull(iptc.getStringArray(IptcDirectory.TAG_KEYWORDS))
        assertEquals("Fresh caption", iptc.getString(IptcDirectory.TAG_CAPTION))
        assertEquals("20240102", iptc.getString(IptcDirectory.TAG_DATE_CREATED))
    }

    @Test
    fun testXmpPreservesUnknownPropertiesAndWritesProfessionalRights() {
        val existingPacket = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                  xmlns:vendor="https://example.com/vendor/1.0/">
                <rdf:Description rdf:about="">
                  <vendor:assetId>asset-42</vendor:assetId>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        val generatedPacket = XmpWriter.buildTemplatePacket(
            MetadataTemplate(
                id = "rights",
                headline = "New headline",
                copyrightOwner = "News Agency",
                copyrightStatus = "Rights protected",
                rightsUsageTerms = "Editorial use only",
                enabledFields = listOf(
                    "headline",
                    "copyrightOwner",
                    "copyrightStatus",
                    "rightsUsageTerms"
                )
            )
        )
        val packet = XmpWriter.mergePreservingUnknownProperties(
            existingPacket,
            generatedPacket
        )
        val doc = parseXml(packet)

        assertEquals("asset-42", doc.firstText("vendor:assetId"))
        assertEquals("New headline", doc.firstText("photoshop:Headline"))
        assertEquals("News Agency", doc.firstText("plus:CopyrightOwnerName"))
        assertEquals("true", doc.firstText("xmpRights:Marked"))
        assertEquals("Editorial use only", doc.firstText("xmpRights:UsageTerms"))
        assertTrue(doc.firstText("photoshop:DateCreated").isNotBlank())
    }

    @Test
    fun testXmpReplacementDropsManagedLegacyPropertiesOnly() {
        val existingPacket = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                  xmlns:dc="http://purl.org/dc/elements/1.1/"
                  xmlns:photoshop="http://ns.adobe.com/photoshop/1.0/"
                  xmlns:vendor="https://example.com/vendor/1.0/">
                <rdf:Description rdf:about="">
                  <dc:subject><rdf:Bag><rdf:li>快捷</rdf:li></rdf:Bag></dc:subject>
                  <photoshop:Headline>Old headline</photoshop:Headline>
                  <vendor:assetId>asset-42</vendor:assetId>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        val generatedPacket = XmpWriter.buildTemplatePacket(
            MetadataTemplate(
                id = "replacement",
                caption = "Fresh caption",
                enabledFields = listOf("caption")
            )
        )

        val packet = XmpWriter.mergePreservingUnknownProperties(
            existingPacket = existingPacket,
            generatedPacket = generatedPacket,
            replaceManagedProperties = true
        )
        val doc = parseXml(packet)

        assertEquals("", doc.firstText("dc:subject"))
        assertEquals("", doc.firstText("photoshop:Headline"))
        assertEquals("Fresh caption", doc.firstText("dc:description"))
        assertEquals("asset-42", doc.firstText("vendor:assetId"))
    }

    // 辅助读取 IPTC 方法
    @Test
    fun testVerificationErrorListsFailedMetadataSections() {
        val result = MetadataVerifier.verify(
            photoFile = testFile,
            template = MetadataTemplate(id = "verification_failure"),
            expectGps = false
        )

        // 无 GPS 要求时，缺少拍摄时间不再构成 EXIF 失败；图像完整性由独立检查保证。
        assertTrue(result.exifVerified)
        assertTrue(result.iptcVerified)
        assertFalse(result.xmpVerified)
        assertFalse(result.isFullyVerified)
        assertEquals("Metadata verification failed: XMP", result.error)
    }

    @Test
    fun testVerificationIgnoresQuestionMarkPlaceholderNotWrittenToIptc() {
        val template = MetadataTemplate(
            id = "placeholder",
            standardFields = mapOf(MetadataFieldDefinitions.IIM_CONTACT to "??"),
            enabledFields = listOf(MetadataFieldDefinitions.IIM_CONTACT)
        )
        IptcWriter.writeTemplateMetadata(testFile, template).getOrThrow()

        val result = MetadataVerifier.verify(testFile, template, expectGps = false)

        assertTrue(result.iptcVerified)
    }

    @Test
    fun testVerificationAcceptsStructuredXmpListValues() {
        val template = MetadataTemplate(
            id = "xmp_list",
            standardFields = mapOf(MetadataFieldDefinitions.DC_SUBJECT to "sports, news"),
            enabledFields = listOf(MetadataFieldDefinitions.DC_SUBJECT)
        )
        val packet = XmpWriter.buildTemplatePacket(template)
        val verifyXmp = MetadataVerifier::class.java.getDeclaredMethod(
            "verifyXmp",
            String::class.java,
            MetadataTemplate::class.java
        ).apply { isAccessible = true }

        assertTrue(verifyXmp.invoke(MetadataVerifier, packet, template) as Boolean)
    }

    @Test
    fun testDcRightsUsesCopyrightInsteadOfUsageTerms() {
        val template = MetadataTemplate(
            id = "rights_mapping",
            copyright = "Copyright 2026 吴奎庆",
            rightsUsageTerms = "Editorial use only",
            enabledFields = listOf(
                "copyright",
                "rightsUsageTerms",
                MetadataFieldDefinitions.DC_RIGHTS
            )
        )
        val packet = XmpWriter.buildTemplatePacket(template)
        val root = parseXml(packet)
        val verifyXmp = MetadataVerifier::class.java.getDeclaredMethod(
            "verifyXmp",
            String::class.java,
            MetadataTemplate::class.java
        ).apply { isAccessible = true }

        assertEquals("Copyright 2026 吴奎庆", root.firstText("dc:rights"))
        assertEquals("Editorial use only", root.firstText("xmpRights:UsageTerms"))
        assertTrue(verifyXmp.invoke(MetadataVerifier, packet, template) as Boolean)
    }

    @Test
    fun testCommonCreatorAndCopyrightOverrideConflictingAdvancedValues() {
        val template = MetadataTemplate(
            id = "canonical_common_values",
            iptcAuthor = "吴奎庆",
            copyright = "Copyright 2026 吴奎庆",
            rightsUsageTerms = "Editorial use only",
            standardFields = mapOf(
                MetadataFieldDefinitions.DC_CREATOR to "Stale Creator",
                MetadataFieldDefinitions.DC_RIGHTS to "Stale Rights"
            ),
            enabledFields = listOf(
                "iptcAuthor",
                "copyright",
                "rightsUsageTerms",
                MetadataFieldDefinitions.DC_CREATOR,
                MetadataFieldDefinitions.DC_RIGHTS
            )
        )
        val packet = XmpWriter.buildTemplatePacket(template)
        val root = parseXml(packet)
        val verifyXmp = MetadataVerifier::class.java.getDeclaredMethod(
            "verifyXmp",
            String::class.java,
            MetadataTemplate::class.java
        ).apply { isAccessible = true }

        assertEquals("吴奎庆", root.firstText("dc:creator"))
        assertEquals("Copyright 2026 吴奎庆", root.firstText("dc:rights"))
        assertEquals("Editorial use only", root.firstText("xmpRights:UsageTerms"))
        assertTrue(verifyXmp.invoke(MetadataVerifier, packet, template) as Boolean)
    }

    @Test
    fun testVerifyNestedCreatorContactAddress() {
        val template = MetadataTemplate(
            id = "creator_contact_address",
            standardFields = mapOf(MetadataFieldDefinitions.IPTC_CORE_CREATOR_ADDRESS to "伤害"),
            enabledFields = listOf(MetadataFieldDefinitions.IPTC_CORE_CREATOR_ADDRESS)
        )
        val packet = XmpWriter.buildTemplatePacket(template)
        val verifyXmp = MetadataVerifier::class.java.getDeclaredMethod(
            "verifyXmp",
            String::class.java,
            MetadataTemplate::class.java
        ).apply { isAccessible = true }

        assertTrue(packet.contains("<Iptc4xmpCore:CreatorContactInfo"))
        assertTrue(packet.contains("<Iptc4xmpCore:CiAdrExtadr>伤害</Iptc4xmpCore:CiAdrExtadr>"))
        assertTrue(verifyXmp.invoke(MetadataVerifier, packet, template) as Boolean)
    }

    @Test
    fun testCreatorPlaceholderFallsBackToIptcAuthor() {
        val verifyXmp = MetadataVerifier::class.java.getDeclaredMethod(
            "verifyXmp",
            String::class.java,
            MetadataTemplate::class.java
        ).apply { isAccessible = true }

        listOf("?", "？", "??", "？？").forEachIndexed { index, placeholder ->
            val template = MetadataTemplate(
                id = "creator_placeholder_$index",
                iptcAuthor = "吴奎庆",
                standardFields = mapOf(MetadataFieldDefinitions.DC_CREATOR to placeholder),
                enabledFields = listOf("iptcAuthor", MetadataFieldDefinitions.DC_CREATOR)
            )

            IptcWriter.writeTemplateMetadata(testFile, template).getOrThrow()
            val xmp = XmpWriter.buildTemplatePacket(template)
            val exifSafeXmp = XmpWriter.toExifSafePacket(xmp)

            assertEquals("吴奎庆", requireNotNull(readIptc(testFile)).getString(IptcDirectory.TAG_BY_LINE))
            assertTrue(xmp.contains("吴奎庆"))
            assertFalse(exifSafeXmp.contains("吴奎庆"))
            assertTrue(exifSafeXmp.all { it.code <= 0x7F })
            assertTrue(verifyXmp.invoke(MetadataVerifier, exifSafeXmp, template) as Boolean)
        }
    }

    private fun readIptc(photoFile: File): IptcDirectory? {
        val metadata = ImageMetadataReader.readMetadata(photoFile)
        return metadata.getFirstDirectoryOfType(IptcDirectory::class.java)
    }

    private fun hasIptcRecord(photoFile: File, type: IptcTypes, expectedValue: String): Boolean {
        val byteSource = ByteSource.file(photoFile)
        val metadata = org.apache.commons.imaging.formats.jpeg.JpegImageParser()
            .getPhotoshopMetadata(byteSource, JpegImagingParameters())
        return metadata?.photoshopApp13Data?.records.orEmpty()
            .any { it.iptcType.type == type.type && it.value == expectedValue }
    }

    // 解析 XML 树
    private fun parseXml(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        return document.documentElement
    }

    private fun Element.firstText(tagName: String): String {
        val nodes = getElementsByTagName(tagName)
        if (nodes.length == 0) return ""
        return nodes.item(0).textContent.trim()
    }

}
