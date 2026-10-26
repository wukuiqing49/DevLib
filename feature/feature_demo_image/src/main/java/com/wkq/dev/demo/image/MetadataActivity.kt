package com.wkq.dev.demo.image

import android.content.Context
import android.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.image.databinding.DemoMetadataBinding
import com.wkq.iptc.feature.press.metadata.MetadataTemplateImporter
import com.wkq.router.annotation.Route
import com.wkq.util.exif.ImageMetadataInspector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 元数据宿主页面，支持读取图片字段以及确认后向副本写入作者与描述。 */
@Route(path = "/image/lib_metadata")
class MetadataActivity : DemoActivity<DemoMetadataBinding, MetadataViewModel>() {
    /** 绑定图片来源、读取和写入操作，在 STARTED 期间展示元数据字段。 */
    override fun initView() {
        bindDemo(
            contentBinding.result,
            getString(R.string.demo_metadata_title),
            contentBinding.choose,
            contentBinding.sample,
            contentBinding.inspect,
            contentBinding.write,
        )
        contentBinding.choose.setOnClickListener { pickImages() }
        contentBinding.sample.setOnClickListener { viewModel.sample(applicationContext) }
        contentBinding.inspect.setOnClickListener { viewModel.inspect(applicationContext) }
        contentBinding.write.setOnClickListener {
            confirm(getString(R.string.metadata_confirm)) {
                viewModel.write(
                    applicationContext,
                    contentBinding.artist.text.toString(),
                    contentBinding.description.text.toString(),
                )
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.fields.collect { fields -> contentBinding.fields.text = fields }
            }
        }
    }
}

/** 在 IO 读取图片元数据并写入副本，向页面发布字段详情与处理结果。 */
class MetadataViewModel : DemoViewModel() {
    private val mutableFields = MutableStateFlow("")
    val fields = mutableFields.asStateFlow()

    /** 读取常见 EXIF 字段并对 JPEG 尝试解析 IPTC，保留标准字段名，非 JPEG 提示能力边界；由 IO 调用。 */
    private fun details(context: Context, file: java.io.File): String {
        val exif = ExifInterface(file.path)
        val groups = buildList {
            add(context.getString(R.string.metadata_exif))
            listOf(
                    ExifInterface.TAG_MAKE,
                    ExifInterface.TAG_MODEL,
                    ExifInterface.TAG_DATETIME_ORIGINAL,
                    ExifInterface.TAG_IMAGE_WIDTH,
                    ExifInterface.TAG_IMAGE_LENGTH,
                    ExifInterface.TAG_EXPOSURE_TIME,
                    ExifInterface.TAG_F_NUMBER,
                    ExifInterface.TAG_ISO_SPEED_RATINGS,
                    ExifInterface.TAG_ARTIST,
                    ExifInterface.TAG_COPYRIGHT,
                    ExifInterface.TAG_IMAGE_DESCRIPTION,
                    ExifInterface.TAG_GPS_LATITUDE,
                    ExifInterface.TAG_GPS_LONGITUDE,
                )
                .forEach { tag ->
                    exif.getAttribute(tag)?.takeIf { it.isNotBlank() }?.let { add("$tag: $it") }
                }
            add(context.getString(R.string.metadata_iptc))
            if (file.extension.lowercase() in setOf("jpg", "jpeg")) {
                val template = MetadataTemplateImporter.readJpegForPreview(file).getOrNull()
                if (template == null) add(context.getString(R.string.metadata_partial))
                else {
                    listOf(
                            "Title" to template.title,
                            "Headline" to template.headline,
                            "Caption" to template.caption,
                            "Creator" to template.creator,
                            "Copyright" to template.copyright,
                            "Keywords" to template.keywords.joinToString(", "),
                        )
                        .filter { it.second.isNotBlank() }
                        .forEach { add("${it.first}: ${it.second}") }
                    template.standardFields.entries.take(60).forEach {
                        add("${it.key}: ${it.value}")
                    }
                }
            } else add(context.getString(R.string.metadata_jpeg_only))
        }
        return groups.joinToString("\n")
    }

    /** 在 IO 统计元数据并读取字段详情，将分类数量和作者描述发布为结果。 */
    fun inspect(context: Context) =
        execute(context) {
            val data = ImageMetadataInspector.inspect(input()).getOrThrow()
            val exif = ExifInterface(input().path)
            mutableFields.value = details(context, input())
            DemoResult(
                "EXIF=${data.exifCount} · IPTC=${data.iptcCount} · XMP=${data.xmpCount} · Other=${data.otherCount}\nArtist=${exif.getAttribute(ExifInterface.TAG_ARTIST).orEmpty()}\n${exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION).orEmpty()}"
            )
        }

    /** 先复制输入再修改作者与描述，写入后重读验证作者字段并发布副本，原素材保持不变。 */
    fun write(context: Context, artist: String, description: String) =
        execute(context) {
            val output = DemoFiles.output(context, input().extension)
            input().copyTo(output, true)
            ExifInterface(output.path).apply {
                setAttribute(ExifInterface.TAG_ARTIST, artist)
                setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, description)
                saveAttributes()
            }
            check(
                ExifInterface(output.path).getAttribute(ExifInterface.TAG_ARTIST).orEmpty() ==
                    artist
            )
            val data = ImageMetadataInspector.inspect(output).getOrThrow()
            mutableFields.value = details(context, output)
            DemoResult(
                "EXIF=${data.exifCount} · IPTC=${data.iptcCount} · XMP=${data.xmpCount}",
                output,
                output,
            )
        }
}
