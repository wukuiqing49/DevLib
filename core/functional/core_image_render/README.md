# core_image_render

水印、标注、样机、相框和签名提取等轻量 Bitmap 渲染。

## 定位与依赖

- 分类：独立功能库；来源：PhotoKit。
- 物理目录：`core/functional/core_image_render`；Gradle 路径：`:core:core_image_render`。
- Android AAR；模块声明 minSdk 24，compileSdk 37；JVM target 17。这是模块声明值，实际消费下限还受传递依赖与 Manifest 合并约束。
- 本仓模块依赖：`api :core:lib_operations`

## 主要入口与调用流程

[BitmapFileDecoder](src/main/java/com/wkq/photo/imagerender/BitmapFileDecoder.kt)、[WatermarkRenderer](src/main/java/com/wkq/photo/imagerender/WatermarkRenderer.kt)、[AnnotationRenderer](src/main/java/com/wkq/photo/imagerender/AnnotationRenderer.kt)、[DeviceMockupRenderer](src/main/java/com/wkq/photo/imagerender/DeviceMockupRenderer.kt)、[PolaroidFrameRenderer](src/main/java/com/wkq/photo/imagerender/PolaroidFrameRenderer.kt)、[SignatureExtractorRenderer](src/main/java/com/wkq/photo/imagerender/SignatureExtractorRenderer.kt)。

选择图片 → 配置采样/像素预算及样式 → 后台渲染 → 保存或释放结果 Bitmap。

不包含美颜或人像模型；Bitmap 所有权、并发预算、输出授权与文件历史由宿主管理。

## 接入

消费模块 `dependencies` 中添加：

```groovy
implementation project(':core:core_image_render')
```

## 可靠性与接入边界（2026-10-27）

解码预算同时检查最终尺寸和实际采样中间位图，避免目标缩小但解码峰值超限。旋转、缩放、可变副本或进度回调失败时释放本次拥有的 Bitmap；成功返回的 Bitmap 由调用者 recycle。

EXIF 方向处理沿用既有实现；像素预算不等于整个进程内存上限，宿主应按设备能力配置预算并限制并发。标注、水印、样机、相框和签名处理仍共用现有引擎。

## 产物发布与本地检查

标准 Maven 坐标：`com.wkq.dev:core-image-render:0.1.0-SNAPSHOT`。默认版本统一来自根 `devlibVersion`；网络库另支持发布版本覆盖。不要同时依赖同库源码模块与旧 Maven AAR/JAR。

以下命令在 DevLib 根目录执行：

```powershell
.\gradlew.bat :core:core_image_render:assembleDebug :core:core_image_render:testDebugUnitTest
.\gradlew.bat :core:core_image_render:publishReleasePublicationToDevLibLocalRepository
```

本地仓库位于根 `build/repo`。有本仓模块依赖时，先运行根 `publishLibrariesToLocalRepository` 发布完整依赖和插件 marker；不要只复制一个 AAR/JAR。消费工程添加对应文件 Maven 仓库，再按标准坐标接入。

## 工作台与验证状态

实际调用示例：[WatermarkActivity](../../../feature/feature_demo_image/src/main/java/com/wkq/dev/demo/image/WatermarkActivity.kt)。入口由工作台对应分类进入；演示适配不属于本库依赖。

来源快照见 [import-manifest](../../../docs/import-manifest.json)；当前分类见 [library-catalog](../../../docs/library-catalog.json)；逐项验证及未验证边界见 [固定测试报告](../../../doc/testing/TEST_REPORT.md)。本页命令是复核入口，不代表本轮已重新执行。源码迁入、构建和 JVM 测试不等于真机、服务器、视觉或生产验收。

[返回项目说明](../../../README.md) · [库目录](../../README.md)
