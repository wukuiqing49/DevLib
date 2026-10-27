package com.wkq.dev.demo.document

import android.content.Context
import android.net.Uri
import com.wkq.dev.document.*
import com.wkq.photo.core.utils.AtomicFileOutput
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 宿主默认资料与模板集合，独立库不依赖此持久化实现。 */
data class RecordSettingsData(val templates: List<RecordTemplate>, val selected: RecordTemplate,
    val company: RecordCompany, val person: RecordPerson, val signatures: List<RecordSignature>)

/** 在应用私有目录保存默认资料、模板和资产；历史导出有独立快照，不受这里的编辑影响。 */
class RecordSettingsStore(private val context: Context) {
    private val root = File(context.filesDir, "record-config").apply { mkdirs() }
    val assets = File(root, "assets").apply { mkdirs() }
    private val templates = File(root, "templates").apply { mkdirs() }
    private val profile = File(root, "profiles.json")

    /** 初始化三种通用模板并读取当前选择、公司、个人和多人签署资料；后台线程调用。 */
    fun load(): RecordSettingsData {
        if (templates.listFiles().orEmpty().none { it.extension == "json" }) {
            listOf(RecordTemplate(id = "standard", name = context.getString(R.string.settings_standard)),
                RecordTemplate(id = "details", name = context.getString(R.string.settings_details), blocks = listOf(RecordBlock.TITLE, RecordBlock.COMPANY, RecordBlock.PERSON, RecordBlock.FIELDS, RecordBlock.DETAILS, RecordBlock.NOTES, RecordBlock.SIGNATURES)),
                RecordTemplate(id = "photos", name = context.getString(R.string.settings_photos), imageColumns = 2,
                    blocks = listOf(RecordBlock.TITLE, RecordBlock.COMPANY, RecordBlock.PERSON, RecordBlock.IMAGES, RecordBlock.NOTES, RecordBlock.FIELDS, RecordBlock.DETAILS, RecordBlock.SIGNATURES)))
                .forEach { saveTemplate(it.copy(labels = blockLabels(), fieldLabels = fieldLabels())) }
        }
        val items = templates.listFiles().orEmpty().filter { it.extension == "json" }.sortedBy { it.name }.map {
            require(it.length() <= RecordTemplateCodec.MAX_BYTES)
            RecordTemplateCodec.decode(it.readText(Charsets.UTF_8), assets)
        }
        require(items.isNotEmpty() && items.size <= 100)
        val json = if (profile.isFile) JSONObject(profile.readText(Charsets.UTF_8)) else JSONObject()
        val c = json.optJSONObject("company") ?: JSONObject(); val p = json.optJSONObject("person") ?: JSONObject()
        val selected = items.firstOrNull { it.id == json.optString("selected", "standard") } ?: items.first()
        val signatures = json.optJSONArray("signatures") ?: JSONArray()
        return RecordSettingsData(items, selected,
            RecordCompany(c.optString("name"), c.optString("address"), c.optString("phone"), c.optString("email"), c.optString("website"), c.optString("registrationNumber"), localImage(c.optString("logo"))),
            RecordPerson(p.optString("name"), p.optString("role"), p.optString("organization"), p.optString("phone"), p.optString("email"), localImage(p.optString("avatar"))),
            (0 until signatures.length()).map { index -> signatures.getJSONObject(index).let {
                RecordSignature(it.getString("name"), it.optString("role"), it.optString("signedAt"), it.optString("statement"), localImage(it.optString("image")))
            } })
    }

    /** 保存模板并更新默认资料；验证后原子替换，失败不覆盖已有资料文件。 */
    fun save(data: RecordSettingsData) {
        ExportRecord("validation", company = data.company, person = data.person, signatures = data.signatures, template = data.selected).validate()
        saveTemplate(data.selected)
        val c = data.company; val p = data.person
        val json = JSONObject().put("selected", data.selected.id)
            .put("company", JSONObject().put("name", c.name).put("address", c.address).put("phone", c.phone).put("email", c.email)
                .put("website", c.website).put("registrationNumber", c.registrationNumber).put("logo", c.logo?.name.orEmpty()))
            .put("person", JSONObject().put("name", p.name).put("role", p.role).put("organization", p.organization).put("phone", p.phone)
                .put("email", p.email).put("avatar", p.avatar?.name.orEmpty()))
            .put("signatures", JSONArray(data.signatures.map {
                JSONObject().put("name", it.name).put("role", it.role).put("signedAt", it.signedAt).put("statement", it.statement).put("image", it.image?.name.orEmpty())
            }))
        AtomicFileOutput.write(profile) { it.writeText(json.toString(), Charsets.UTF_8) }
    }

    /** 单独持久化已校验模板，限制数量，不允许模板 ID 形成路径。 */
    fun saveTemplate(template: RecordTemplate) {
        template.validate()
        val file = File(templates, "${template.id}.json")
        require(file.exists() || templates.listFiles().orEmpty().size < 100)
        AtomicFileOutput.write(file) { it.writeText(RecordTemplateCodec.encode(template), Charsets.UTF_8) }
    }

    /** 删除模板但至少保留一个，调用方负责用户确认。 */
    fun deleteTemplate(template: RecordTemplate) {
        template.validate(); require(templates.listFiles().orEmpty().count { it.extension == "json" } > 1)
        check(File(templates, "${template.id}.json").delete())
    }

    /** 有限读取外部模板，导入时分配新 ID，避免覆盖同名的本地模板。 */
    fun importTemplate(uri: Uri): RecordTemplate {
        val text = context.contentResolver.openInputStream(uri)?.use {
            val bytes = readLimited(it, RecordTemplateCodec.MAX_BYTES)
            require(bytes.size <= RecordTemplateCodec.MAX_BYTES); bytes.toString(Charsets.UTF_8)
        } ?: error("Missing template")
        val template = RecordTemplateCodec.decode(text, assets).copy(id = UUID.randomUUID().toString(), revision = 1)
        template.watermark.image?.let { file ->
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 200_000_000)
            require(bounds.outMimeType == if (file.extension == "png") "image/png" else "image/jpeg")
        }
        saveTemplate(template)
        return template
    }

    /** 将用户选择的 JPEG/PNG 资料图片复制进私有资产，大小限 2 MiB，不依赖临时 URI 授权。 */
    fun importImage(uri: Uri): File {
        val file = File(assets, "${UUID.randomUUID()}.png")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = readLimited(input, 2 * 1024 * 1024)
                require(bytes.size in 1..2 * 1024 * 1024)
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outMimeType in setOf("image/png", "image/jpeg") && bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 200_000_000)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
                val bitmap = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }))
                try { file.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { bitmap.recycle() }
            } ?: error("Missing image")
            require(file.length() <= 2 * 1024 * 1024)
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            return file
        } catch (error: Exception) { file.delete(); throw error }
    }

    /** 在 API 26 起按块有限读取外部输入，避免依赖较新平台的 readNBytes。 */
    private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {
        val result = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            val count = input.read(buffer)
            if (count < 0) break
            require(result.size() + count <= limit)
            result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }

    /** 将私有资产文件名恢复为文件，拒绝路径穿越和失效引用。 */
    private fun localImage(name: String): File? = name.takeIf { it.isNotEmpty() }?.let {
        val file = File(assets, it).canonicalFile
        require(file.toPath().startsWith(assets.canonicalFile.toPath()) && file.isFile)
        file
    }

    /** 默认区块标签来自宿主资源，独立库不固定产品语言。 */
    private fun blockLabels(): Map<RecordBlock, String> = RecordBlock.entries.zip(listOf(
        R.string.settings_block_title, R.string.settings_block_company, R.string.settings_block_person, R.string.settings_block_fields,
        R.string.settings_block_notes, R.string.settings_block_details, R.string.settings_block_images, R.string.settings_block_signatures))
        .associate { it.first to context.getString(it.second) }

    /** 默认资料字段标签与表单一致，模板交换时可保留或自定义。 */
    private fun fieldLabels(): Map<String, String> = mapOf("company_name" to R.string.settings_company_name,
        "address" to R.string.settings_address, "phone" to R.string.settings_phone, "email" to R.string.settings_email,
        "website" to R.string.settings_website, "registration_number" to R.string.settings_registration,
        "person_name" to R.string.settings_person_name, "role" to R.string.settings_role, "organization" to R.string.settings_organization,
        "signer" to R.string.settings_signer, "signed_at" to R.string.settings_signed_at, "statement" to R.string.settings_statement)
        .mapValues { context.getString(it.value) }
}
