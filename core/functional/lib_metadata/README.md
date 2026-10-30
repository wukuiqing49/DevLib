# lib_metadata

EXIF/IPTC/XMP、sidecar、隐私清理、格式校验与元数据模板。

## 定位与依赖

- 分类：独立功能库；来源：PressIPTC。
- 物理目录：`core/functional/lib_metadata`；Gradle 路径：`:core:lib_metadata`。
- Android AAR；模块声明 minSdk 23，compileSdk 37；JVM target 17。这是模块声明值，实际消费下限还受传递依赖与 Manifest 合并约束。
- 本仓模块依赖：无其他 core 模块依赖。

## 主要入口与调用流程

[MetadataRecord](src/main/java/com/wkq/dev/metadata/model/MetadataRecord.kt)、[ExifWriter](src/main/java/com/wkq/iptc/camera/metadata/ExifWriter.kt)、[IptcWriter](src/main/java/com/wkq/iptc/feature/press/metadata/IptcWriter.kt)、[XmpWriter](src/main/java/com/wkq/iptc/feature/press/metadata/XmpWriter.kt)、[ImageMetadataInspector](src/main/java/com/wkq/util/exif/ImageMetadataInspector.kt)、[MetadataScrubber](src/main/java/com/wkq/util/exif/MetadataScrubber.kt)。

将业务记录映射为 MetadataRecord → 根据格式选择读写入口 → 保护原图并校验输出 → 宿主保存结果。

基础读写、高级 native、Room 模板与保护服务属于不同使用路径，但当前 AAR 仍携带相应依赖和资源；ABI、16KB 及复杂格式需专项验证。

## 接入

消费模块 `dependencies` 中添加：

```groovy
implementation project(':core:lib_metadata')
```

## 可靠性与接入边界（2026-10-27）

基础 ExifWriter.writeAllMetadata 在同目录副本完成 EXIF 和可选 XMP 后再替换源文件，避免后半程失败留下只写了一半的元数据。writeBasicMetadata 复用该事务，缺失原始拍摄时间保持未知。相同文件的并发业务操作由宿主串行调度，其他高级写入 API 沿用各自原有契约。

接入分三条路径：基础 EXIF/XMP 读写；高级格式/native 引擎；模板 Room 存储与原生任务保护服务。只有调用相应入口才执行相关路径，但当前 AAR 仍包含这些依赖及 native 资源，并未实现按需下载或物理拆包。不要把依赖本库描述成完全不含数据库、服务或 native。

原图完整性保护沿用各格式校验；native ABI/16KB、HEIF、后台限制和复杂格式仍需专项验收。迁入与 JVM 测试不等于原图所有格式均可无损写入。

## 产物发布与本地检查

标准 Maven 坐标：`com.wkq.dev:lib-metadata:0.1.0-SNAPSHOT`。默认版本统一来自根 `devlibVersion`；网络库另支持发布版本覆盖。不要同时依赖同库源码模块与旧 Maven AAR/JAR。

以下命令在 DevLib 根目录执行：

```powershell
.\gradlew.bat :core:lib_metadata:assembleDebug :core:lib_metadata:testDebugUnitTest
.\gradlew.bat :core:lib_metadata:publishReleasePublicationToDevLibLocalRepository
```

本地仓库位于根 `build/repo`。有本仓模块依赖时，先运行根 `publishLibrariesToLocalRepository` 发布完整依赖和插件 marker；不要只复制一个 AAR/JAR。消费工程添加对应文件 Maven 仓库，再按标准坐标接入。

## 工作台与验证状态

实际调用示例：[MetadataActivity](../../../feature/feature_demo_image/src/main/java/com/wkq/dev/demo/image/MetadataActivity.kt)。入口由工作台对应分类进入；演示适配不属于本库依赖。

来源快照见 [import-manifest](../../../docs/import-manifest.json)；当前分类见 [library-catalog](../../../docs/library-catalog.json)；逐项验证及未验证边界见 [固定测试报告](../../../doc/testing/TEST_REPORT.md)。本页命令是复核入口，不代表本轮已重新执行。源码迁入、构建和 JVM 测试不等于真机、服务器、视觉或生产验收。

[返回项目说明](../../../README.md) · [库目录](../../README.md)
