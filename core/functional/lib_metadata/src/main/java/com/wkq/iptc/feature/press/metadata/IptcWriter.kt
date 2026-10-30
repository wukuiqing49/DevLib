package com.wkq.iptc.feature.press.metadata

import com.wkq.dev.metadata.model.MetadataRecord
import com.wkq.dev.metadata.model.MetadataFieldDefinitions
import com.wkq.dev.metadata.model.MetadataTemplate
import org.apache.commons.imaging.bytesource.ByteSource
import org.apache.commons.imaging.formats.jpeg.JpegImagingParameters
import org.apache.commons.imaging.formats.jpeg.iptc.IptcRecord
import org.apache.commons.imaging.formats.jpeg.iptc.IptcTypes
import org.apache.commons.imaging.formats.jpeg.iptc.JpegIptcRewriter
import org.apache.commons.imaging.formats.jpeg.iptc.PhotoshopApp13Data
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object IptcWriter {

    fun writeTemplateMetadata(
        photoFile: File,
        template: MetadataTemplate,
        replaceManagedFields: Boolean = false
    ): Result<Unit> {
        if (!com.wkq.util.exif.CleanJpegExporter.isJpeg(photoFile)) {
            // PNG/WebP通过标准XMP表达IPTC字段，不写JPEG专用APP13。
            return runCatching {
                MetadataXmpStore.write(photoFile, XmpWriter.buildTemplatePacket(
                    photoFile, template, replaceManagedFields)).getOrThrow()
            }
        }
        return writeRecords(
            photoFile = photoFile,
            records = templateToRecords(template),
            fieldsToClear = if (replaceManagedFields) MANAGED_IIM_TYPES else emptySet()
        )
    }

    fun writeRecordMetadata(photoFile: File, record: MetadataRecord): Result<Unit> {
        if (!com.wkq.util.exif.CleanJpegExporter.isJpeg(photoFile)) {
            return XmpWriter.writeRecordMetadata(photoFile, record)
        }
        return writeRecords(photoFile, recordToRecords(record))
    }

    private fun writeRecords(
        photoFile: File,
        records: List<IptcRecord>,
        fieldsToClear: Set<Int> = emptySet()
    ): Result<Unit> {
        return runCatching {
            // 使用 ByteSource.file() 流式读取，不再 readBytes() 全量加载到内存
            val byteSource = ByteSource.file(photoFile)
            val metadata = org.apache.commons.imaging.formats.jpeg.JpegImageParser()
                .getPhotoshopMetadata(byteSource, JpegImagingParameters())

            val existingRecords = metadata?.photoshopApp13Data?.records.orEmpty()
            val newTypes = records.map { it.iptcType.type }.toSet() + fieldsToClear
            val mergedList = mutableListOf<IptcRecord>()

            // 保留未被修改的现有记录
            for (rec in existingRecords) {
                if (rec.iptcType.type !in newTypes) {
                    mergedList.add(rec)
                }
            }
            // 添加新记录
            mergedList.addAll(records.mapNotNull { it.cleanValueRecordOrNull() })

            // 去重单值字段
            val finalRecords = deduplicate(mergedList)

            val nonIptcBlocks = metadata?.photoshopApp13Data?.nonIptcBlocks.orEmpty()
            val app13Data = PhotoshopApp13Data(finalRecords, nonIptcBlocks, true)

            // 写入临时文件，避免写入过程中中断导致原文件损坏
            val tempFile = File(photoFile.parent, "${photoFile.name}.iptc_tmp")
            try {
                tempFile.outputStream().buffered().use { out ->
                    JpegIptcRewriter().writeIptc(byteSource, out, app13Data)
                }
                // 原子替换
                if (!tempFile.renameTo(photoFile)) {
                    // renameTo 跨分区时可能失败，兜底用流拷贝
                    tempFile.inputStream().use { input ->
                        photoFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    tempFile.delete()
                }
            } catch (e: Exception) {
                tempFile.delete()
                throw e
            }
        }
    }

    private fun deduplicate(list: List<IptcRecord>): List<IptcRecord> {
        val singleValueMap = mutableMapOf<Int, IptcRecord>()
        val multiValueList = mutableListOf<IptcRecord>()
        val seenMultiValues = mutableSetOf<Pair<Int, String>>()

        for (record in list) {
            val type = record.iptcType.type
            if (type == IptcTypes.KEYWORDS.type || type == IptcTypes.SUPPLEMENTAL_CATEGORY.type) {
                if (seenMultiValues.add(type to record.value)) {
                    multiValueList.add(record)
                }
            } else {
                singleValueMap[type] = record
            }
        }

        val finalResult = mutableListOf<IptcRecord>()
        val addedMultiValues = mutableSetOf<Pair<Int, String>>()

        for (record in list) {
            val type = record.iptcType.type
            if (type == IptcTypes.KEYWORDS.type || type == IptcTypes.SUPPLEMENTAL_CATEGORY.type) {
                if (record in multiValueList && addedMultiValues.add(type to record.value)) {
                    finalResult.add(record)
                }
            } else {
                if (record === singleValueMap[type]) {
                    finalResult.add(record)
                }
            }
        }

        return finalResult
    }

    private fun templateToRecords(template: MetadataTemplate): List<IptcRecord> {
        val now = Date()
        return buildList {
            addEnabled(template, IptcTypes.OBJECT_NAME, "title", template.title)
            addEnabled(template, IptcTypes.HEADLINE, "headline", template.headline)
            addEnabled(template, IptcTypes.CAPTION_ABSTRACT, "caption", template.caption)
            addEnabled(template, IptcTypes.BYLINE, "iptcAuthor", template.iptcAuthor.ifBlank { template.creator })
            addEnabled(template, IptcTypes.WRITER_EDITOR, "writer", template.writer)
            addEnabled(template, IptcTypes.CATEGORY, "category", template.category)
            addEnabled(template, IptcTypes.CITY, "city", template.city)
            addEnabled(template, IptcTypes.SUBLOCATION, "location", template.location)
            addEnabled(template, IptcTypes.PROVINCE_STATE, "state", template.state)
            addEnabled(template, IptcTypes.COUNTRY_PRIMARY_LOCATION_NAME, "country", template.country)
            addEnabled(template, IptcTypes.COUNTRY_PRIMARY_LOCATION_CODE, "isoCountryCode", template.isoCountryCode)
            addEnabled(template, IptcTypes.CREDIT, "credit", template.credit)
            addEnabled(template, IptcTypes.SOURCE, "source", template.source)
            addEnabled(template, IptcTypes.COPYRIGHT_NOTICE, "copyright", template.copyright)
            addEnabled(template, IptcTypes.CONTACT, "email", template.email)
            if (template.isFieldEnabled("keywords")) {
                template.keywords.forEach { addClean(IptcTypes.KEYWORDS, it) }
            }

            addStandard(template, IptcTypes.RECORD_VERSION, MetadataFieldDefinitions.IIM_RECORD_VERSION)
            addStandard(template, IptcTypes.OBJECT_TYPE_REFERENCE, MetadataFieldDefinitions.IIM_OBJECT_TYPE)
            addStandard(template, IptcTypes.OBJECT_ATTRIBUTE_REFERENCE, MetadataFieldDefinitions.IIM_OBJECT_ATTRIBUTE)
            addStandard(template, IptcTypes.OBJECT_NAME, MetadataFieldDefinitions.IIM_OBJECT_NAME)
            addStandard(template, IptcTypes.EDIT_STATUS, MetadataFieldDefinitions.IIM_EDIT_STATUS)
            addStandard(template, IptcTypes.URGENCY, MetadataFieldDefinitions.IIM_URGENCY)
            addStandard(template, IptcTypes.CATEGORY, MetadataFieldDefinitions.IIM_CATEGORY)
            addStandard(template, IptcTypes.SUPPLEMENTAL_CATEGORY, MetadataFieldDefinitions.IIM_SUPPLEMENTAL_CATEGORY)
            addStandardList(template, IptcTypes.KEYWORDS, MetadataFieldDefinitions.IIM_KEYWORDS)
            addStandard(template, IptcTypes.SPECIAL_INSTRUCTIONS, MetadataFieldDefinitions.IIM_SPECIAL_INSTRUCTIONS)
            val dateVal = template.standardValue(MetadataFieldDefinitions.IIM_DATE_CREATED)
                .takeIf { it.isNotBlank() }
            val timeVal = template.standardValue(MetadataFieldDefinitions.IIM_TIME_CREATED)
                .takeIf { it.isNotBlank() }
            if (dateVal != null) addClean(IptcTypes.DATE_CREATED, dateVal)
            if (timeVal != null) addClean(IptcTypes.TIME_CREATED, timeVal)
            addStandard(
                template,
                IptcTypes.ORIGINAL_TRANSMISSION_REFERENCE,
                MetadataFieldDefinitions.IIM_JOB_IDENTIFIER
            )
            addStandard(template, IptcTypes.BYLINE, MetadataFieldDefinitions.IIM_BYLINE)
            addStandard(template, IptcTypes.BYLINE_TITLE, MetadataFieldDefinitions.IIM_BYLINE_TITLE)
            addStandard(template, IptcTypes.CITY, MetadataFieldDefinitions.IIM_CITY)
            addStandard(template, IptcTypes.SUBLOCATION, MetadataFieldDefinitions.IIM_SUBLOCATION)
            addStandard(template, IptcTypes.PROVINCE_STATE, MetadataFieldDefinitions.IIM_STATE)
            addStandard(template, IptcTypes.COUNTRY_PRIMARY_LOCATION_CODE, MetadataFieldDefinitions.IIM_COUNTRY_CODE)
            addStandard(template, IptcTypes.COUNTRY_PRIMARY_LOCATION_NAME, MetadataFieldDefinitions.IIM_COUNTRY_NAME)
            addStandard(template, IptcTypes.HEADLINE, MetadataFieldDefinitions.IIM_HEADLINE)
            addStandard(template, IptcTypes.CREDIT, MetadataFieldDefinitions.IIM_CREDIT)
            addStandard(template, IptcTypes.SOURCE, MetadataFieldDefinitions.IIM_SOURCE)
            addStandard(template, IptcTypes.COPYRIGHT_NOTICE, MetadataFieldDefinitions.IIM_COPYRIGHT)
            addStandard(template, IptcTypes.CONTACT, MetadataFieldDefinitions.IIM_CONTACT)
            addStandard(template, IptcTypes.CAPTION_ABSTRACT, MetadataFieldDefinitions.IIM_CAPTION)
            addStandard(template, IptcTypes.WRITER_EDITOR, MetadataFieldDefinitions.IIM_WRITER)
        }.mapNotNull { it.cleanValueRecordOrNull() }
    }

    private fun recordToRecords(record: MetadataRecord): List<IptcRecord> {
        return buildList {
            addClean(IptcTypes.OBJECT_NAME, record.title)
            addClean(IptcTypes.HEADLINE, record.headline)
            addClean(IptcTypes.CAPTION_ABSTRACT, record.caption)
            addClean(IptcTypes.BYLINE, record.iptcAuthor.ifBlank { record.creator })
            addClean(IptcTypes.WRITER_EDITOR, record.writer)
            addClean(IptcTypes.CATEGORY, record.category)
            addClean(IptcTypes.CITY, record.city)
            addClean(IptcTypes.PROVINCE_STATE, record.state)
            addClean(IptcTypes.COUNTRY_PRIMARY_LOCATION_NAME, record.country)
            addClean(IptcTypes.COUNTRY_PRIMARY_LOCATION_CODE, record.isoCountryCode)
            addClean(IptcTypes.CREDIT, record.credit)
            addClean(IptcTypes.SOURCE, record.source)
            addClean(IptcTypes.COPYRIGHT_NOTICE, record.copyright)
            record.keywords.split(",").map { it.trim() }.filter { it.isNotBlank() }
                .forEach { addClean(IptcTypes.KEYWORDS, it) }
        }.mapNotNull { it.cleanValueRecordOrNull() }
    }

    private fun MutableList<IptcRecord>.addEnabled(
        template: MetadataTemplate,
        type: IptcTypes,
        field: String,
        value: String
    ) {
        if (template.isFieldEnabled(field)) {
            addClean(type, value)
        }
    }

    private fun MutableList<IptcRecord>.addStandard(
        template: MetadataTemplate,
        type: IptcTypes,
        field: String,
        fallback: String = ""
    ) {
        if (!template.isFieldEnabled(field)) return
        val value = template.standardValue(field).ifBlank { fallback }
        addClean(type, value)
    }

    private fun MutableList<IptcRecord>.addStandardList(
        template: MetadataTemplate,
        type: IptcTypes,
        field: String
    ) {
        if (!template.isFieldEnabled(field)) return
        template.standardValue(field)
            .split(",", "\uFF0C", ";", "\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { addClean(type, it) }
    }

    private fun MutableList<IptcRecord>.addClean(type: IptcTypes, value: String) {
        val clean = value.cleanIptcText()
        if (clean.isNotBlank()) {
            add(IptcRecord(type, clean))
        }
    }

    private fun IptcRecord.cleanValueRecordOrNull(): IptcRecord? {
        val clean = value.cleanIptcText()
        return clean.takeIf(String::isNotBlank)?.let { IptcRecord(iptcType, it) }
    }

    private fun String.cleanIptcText(): String {
        val clean = trim()
        return clean.takeUnless { it.isQuestionMarkPlaceholder() }.orEmpty()
    }

    private fun String.isQuestionMarkPlaceholder(): Boolean {
        val compact = filterNot(Char::isWhitespace)
        return compact.length >= 2 && compact.all { it == '?' || it.code == 0xFF1F }
    }

    /**
     * PressIPTC 明确管理的 IIM 字段。批量“重新封装”时清理这些旧值，
     * 但保留拍摄日期、相机 EXIF、未知 IPTC 数据集及第三方 Photoshop 块。
     */
    private val MANAGED_IIM_TYPES = setOf(
        IptcTypes.OBJECT_TYPE_REFERENCE.type,
        IptcTypes.OBJECT_ATTRIBUTE_REFERENCE.type,
        IptcTypes.OBJECT_NAME.type,
        IptcTypes.EDIT_STATUS.type,
        IptcTypes.URGENCY.type,
        IptcTypes.CATEGORY.type,
        IptcTypes.SUPPLEMENTAL_CATEGORY.type,
        IptcTypes.KEYWORDS.type,
        IptcTypes.SPECIAL_INSTRUCTIONS.type,
        IptcTypes.BYLINE.type,
        IptcTypes.BYLINE_TITLE.type,
        IptcTypes.CITY.type,
        IptcTypes.SUBLOCATION.type,
        IptcTypes.PROVINCE_STATE.type,
        IptcTypes.COUNTRY_PRIMARY_LOCATION_CODE.type,
        IptcTypes.COUNTRY_PRIMARY_LOCATION_NAME.type,
        IptcTypes.ORIGINAL_TRANSMISSION_REFERENCE.type,
        IptcTypes.HEADLINE.type,
        IptcTypes.CREDIT.type,
        IptcTypes.SOURCE.type,
        IptcTypes.COPYRIGHT_NOTICE.type,
        IptcTypes.CONTACT.type,
        IptcTypes.CAPTION_ABSTRACT.type,
        IptcTypes.WRITER_EDITOR.type
    )
}
