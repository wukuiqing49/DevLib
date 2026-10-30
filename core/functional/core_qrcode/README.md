# core_qrcode

二维码生成、样式渲染、图片识别和扫码 Activity。

## 定位与依赖

- 分类：独立功能库；来源：PhotoKit。
- 物理目录：`core/functional/core_qrcode`；Gradle 路径：`:core:core_qrcode`。
- Android AAR；模块声明 minSdk 24，compileSdk 37；JVM target 17。这是模块声明值，实际消费下限还受传递依赖与 Manifest 合并约束。
- 本仓模块依赖：`api :core:lib_operations`

## 主要入口与调用流程

[QrCodeKit](src/main/java/com/wkq/photo/qrcode/QrCodeKit.kt)、[QRCodeGenerator](src/main/java/com/wkq/photo/qrcode/QRCodeGenerator.kt)、[QRCodeRenderer](src/main/java/com/wkq/photo/qrcode/QRCodeRenderer.kt)、[QrLiveScanActivity](src/main/java/com/wkq/photo/qrcode/QrLiveScanActivity.kt)。

选择内容与样式 → 生成/回读校验或输入图片识别 → 需要实时扫描时由宿主提供相机授权。

宿主负责相机权限与生命周期；返回 Bitmap 按约释放，不保证低对比度、遮挡或所有印刷场景的识别率。

## 接入

消费模块 `dependencies` 中添加：

```groovy
implementation project(':core:core_qrcode')
```

## 可靠性与接入边界（2026-10-27）

识别多码前拒绝已回收 Bitmap，并限制像素副本为 1600 万像素；超大输入先由宿主采样。生成过程取消、样式回读失败或进度回调抛错时释放临时 Bitmap。实时扫描页面只交付一次结果，结束过程忽略重复回调。

生成保留原有尺寸预算、纠错及可扫码回读；不能保证所有相机、印刷尺寸、低对比度和遮挡条件均能识别。成功返回图片由调用者释放；相机授权、设备表现与视觉验收由宿主完成。

## 产物发布与本地检查

标准 Maven 坐标：`com.wkq.dev:core-qrcode:0.1.0-SNAPSHOT`。默认版本统一来自根 `devlibVersion`；网络库另支持发布版本覆盖。不要同时依赖同库源码模块与旧 Maven AAR/JAR。

以下命令在 DevLib 根目录执行：

```powershell
.\gradlew.bat :core:core_qrcode:assembleDebug :core:core_qrcode:testDebugUnitTest
.\gradlew.bat :core:core_qrcode:publishReleasePublicationToDevLibLocalRepository
```

本地仓库位于根 `build/repo`。有本仓模块依赖时，先运行根 `publishLibrariesToLocalRepository` 发布完整依赖和插件 marker；不要只复制一个 AAR/JAR。消费工程添加对应文件 Maven 仓库，再按标准坐标接入。

## 工作台与验证状态

实际调用示例：[QrActivity](../../../feature/feature_demo_image/src/main/java/com/wkq/dev/demo/image/QrActivity.kt)。入口由工作台对应分类进入；演示适配不属于本库依赖。

来源快照见 [import-manifest](../../../docs/import-manifest.json)；当前分类见 [library-catalog](../../../docs/library-catalog.json)；逐项验证及未验证边界见 [固定测试报告](../../../doc/testing/TEST_REPORT.md)。本页命令是复核入口，不代表本轮已重新执行。源码迁入、构建和 JVM 测试不等于真机、服务器、视觉或生产验收。

[返回项目说明](../../../README.md) · [库目录](../../README.md)
