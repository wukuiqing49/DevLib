# core_document

独立 Android 记录导出库：**同一份记录，生成 PDF、Excel、Word**。从 `core_pdf`（PhotoKit）与 `lib_document`（RoofQuote）合并，不含业务产品模板、计价、账号或付费逻辑。来源快照继续保留在 `docs/import-manifest.json`，原来源路径和散列不改写。

```groovy
implementation project(':core:core_document')
// 发布坐标：com.wkq.dev:core-document:0.1.0-SNAPSHOT
```

```kotlin
val record = ExportRecord(
    title = "Inspection record",
    recordedAt = "2026-10-27 10:00",
    notes = "Inspection notes",
    fields = listOf(RecordField("ID", "00123")),
    rows = listOf(
        listOf(RecordCell("Item"), RecordCell("Quantity")),
        listOf(RecordCell("Device"), RecordCell("12", numeric = true)),
    ),
)
// 必须在后台线程调用；协程宿主使用 runInterruptible 衔接取消。
val template = RecordTemplate(
    id = "company-record", name = "Company record", revision = 1,
    blocks = listOf(RecordBlock.TITLE, RecordBlock.COMPANY, RecordBlock.PERSON,
        RecordBlock.FIELDS, RecordBlock.NOTES, RecordBlock.DETAILS, RecordBlock.IMAGES, RecordBlock.SIGNATURES),
    style = RecordStyle(primaryColor = "2458C6", header = "Company", footer = "Record"),
    watermark = RecordWatermark(WatermarkKind.TEXT, text = "DRAFT"),
)
val configured = record.copy(
    company = RecordCompany(name = "Company", logo = logoFile),
    person = RecordPerson(name = "Author", role = "Engineer"),
    signatures = listOf(RecordSignature(name = "Reviewer", signedAt = "2026-10-27", image = signatureFile)),
    template = template,
)
val files = RecordExporter.export(configured, outputDirectory)
// 单格式：formats = setOf(RecordFormat.PDF)
```

新 API 包名 `com.wkq.dev.document`。旧 `com.wkq.photo.pdf.*` 与 `com.wkq.roof.export.*` 保持不变；仅 Gradle 模块和发布坐标变更。现在统一发布 AAR，不能继续作为纯 JVM JAR 接入。

## 内容与格式

- PDF：A4 横竖向、可配置字号/边距/页眉页脚、文字自然换行、段落及表格行跨页、页码、缩放图片；文字不是整页截图。按模板区块顺序排版，照片支持 1–3 列；表格跨页不重复表头，图片说明允许换到下一页。
- Excel：按启用区块分别组织工作表，顺序跟随模板；标题工作表名为 Record，明细为 Details，明细首行为表头。默认文本，显式数字才写数值，超过 15 位精度应保留文本。无公式计算引擎。
- Word：标题样式、时间、字段表、备注、明细和图片，可在外部编辑器继续编辑。库不提供阅读器或 Office 模板编辑功能。

公司资料支持名称、地址、电话、邮箱、网站、登记编号和 Logo；个人资料支持姓名、角色、单位、电话、邮箱和头像；签署列表支持多人姓名、角色、时间、说明和签名图片。签名图片不表示 PKI 证书签名。

宿主负责本地化字段、时间和记录文案。工作表技术名固定，用户标题完整写入内容，包含斜线等字符不会成为非法工作表名。

## 限制与可靠性

记录标题最多 200 字符；字段 100 项；矩形明细最多 1000 行、8 列；单段/单元格最多 32767 UTF-16 单元，全文最多 100 万单元；最多 20 张 JPEG/PNG 图片，单张源图不超过 2 亿像素。不可识别图片与非法 XML 字符拒绝导出，不静默删改内容。PDF 解码缩采样限制长边约 1600 像素，逐张释放 Bitmap。

后台同步入口通过线程中断协作取消；进度按已完成格式数回调，在调用线程执行。每批在临时目录生成，全部成功后原子重命名为独立目录；失败或取消清理本批临时目录，不改既有产物。文件系统不支持原子移动时明确失败。不覆盖宿主已有文件。返回的文件由宿主负责保存、分享、历史记录和生命周期清理；系统强杀留下的临时目录需宿主按保留策略清理。

原图片 PDF 的 File/Uri/PFD 输出、缩放、网格、页眉页脚等 API 仍可使用。仅 File 输出提供原子写入，Uri/PFD 不能保证回滚。

## 验证

```powershell
.\gradlew.bat :core:core_document:testDebugUnitTest
.\gradlew.bat :core:core_document:assembleDebugAndroidTest
```

JVM 用例验证 Office 内容与 XML、批量回滚、中断和输入边界。设备用例 `RecordPdfInstrumentedTest` 覆盖三格式生成及 PDF 多页打开，编译不代表执行通过。中文字体、长表格、附件排版及 Word/Excel/WPS/LibreOffice 打开效果仍需实际验收。

## 模板、样式与水印

`RecordTemplate` 是结构化记录模板，不是任意 DOCX/XLSX 模板文件解析器。支持区块隐藏/重排、区块标签、资料字段标签、基本字段筛选、明细列筛选/重排、照片列数；`RecordStyle` 控制主色、表头底色、标题/正文字号、方向、边距、页眉页脚与页码。

`RecordWatermark` 支持文字或 JPEG/PNG 图片；透明度 1–40%、角度 -90–90°、宽度 10–90%、横纵中心位置 10–90%、首页或每页。三格式共用透明 PNG 渲染；PDF 按页覆盖，Word 页眉锚点重复，Excel 按工作表打印分页插入图层。Excel 是图片图层，不是应用背景图；不同阅读器、缩放与打印结果仍需验收。

```kotlin
val json = RecordTemplateCodec.encode(template) // 图片水印字节嵌入 JSON
val imported = RecordTemplateCodec.decode(json, privateAssetsDirectory)
```

模板交换上限 4 MiB，图片水印上限 2 MiB；版本、颜色、重复区块/列、数值范围和图片路径均校验。不接受模板指定任意本地路径。模板不包含公司、个人或签署资料。

## 历史快照

每批导出目录包含三个选定格式文件，以及 `record-snapshot.json`、`assets/` 和可选 `watermark.png`。返回列表仅包含用户选定的格式文件。输入图片复制后再生成，后续改默认资料、模板或原图不会改动历史导出。

```kotlin
val previous = RecordSnapshot.restore(previousExportDirectory)
val files = RecordExporter.export(previous, anotherOutputDirectory)
```

快照使用相对资产路径，恢复拒绝越界引用。模板隐藏只影响展示，原记录资料仍保留在私有快照。独立库不管理宿主的默认资料、账号或数据库；宿主决定资料保留与导出目录清理策略。consumer rules 保留快照数据字段，不对引擎进行整库 keep；Release/R8 运行仍需消费项目验收。
