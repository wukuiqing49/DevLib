# core_archive

ZIP 创建、加密归档与受预算限制的解压。

## 定位与依赖

- 分类：独立功能库；来源：PhotoKit。
- 物理目录：`core/functional/core_archive`；Gradle 路径：`:core:core_archive`。
- Android AAR；模块声明 minSdk 24，compileSdk 37；JVM target 17。这是模块声明值，实际消费下限还受传递依赖与 Manifest 合并约束。
- 本仓模块依赖：`api :core:lib_operations`

## 主要入口与调用流程

[ZipArchiveCreator](src/main/java/com/wkq/photo/archive/ZipArchiveCreator.kt)、[ZipArchiveOperations](src/main/java/com/wkq/photo/archive/ZipArchiveOperations.kt)、[EncryptedArchiveExtractor](src/main/java/com/wkq/photo/archive/EncryptedArchiveExtractor.kt)。

取得已授权输入 → 配置密码、输出位置与操作预算 → 后台线程执行 → 仅在成功提交后展示产物。

支持 ZIP，不宣称 RAR/7z；宿主负责密码、空间、文件授权和产物保存。

## 接入

消费模块 `dependencies` 中添加：

```groovy
implementation project(':core:core_archive')
```

## 可靠性与接入边界（2026-10-27）

加密解压先检查压缩输入字节预算，再在隔离目录处理；拒绝绝对路径、点路径、冒号/NUL 和重复规范路径，限制文件数量、单文件与总解压大小。密码错误、损坏内容或取消不会发布隔离目录，发布前再次检查取消。

当前是 ZIP 能力，不宣称 RAR/7z 支持。宿主仍负责授权、输出目录和空间安排；文件系统实际空间与可用性不能仅凭预检查保证。

## 产物发布与本地检查

标准 Maven 坐标：`com.wkq.dev:core-archive:0.1.0-SNAPSHOT`。默认版本统一来自根 `devlibVersion`；网络库另支持发布版本覆盖。不要同时依赖同库源码模块与旧 Maven AAR/JAR。

以下命令在 DevLib 根目录执行：

```powershell
.\gradlew.bat :core:core_archive:assembleDebug :core:core_archive:testDebugUnitTest
.\gradlew.bat :core:core_archive:publishReleasePublicationToDevLibLocalRepository
```

本地仓库位于根 `build/repo`。有本仓模块依赖时，先运行根 `publishLibrariesToLocalRepository` 发布完整依赖和插件 marker；不要只复制一个 AAR/JAR。消费工程添加对应文件 Maven 仓库，再按标准坐标接入。

## 工作台与验证状态

实际调用示例：[ArchiveActivity](../../../feature/feature_demo_files/src/main/java/com/wkq/dev/demo/files/ArchiveActivity.kt)。入口由工作台对应分类进入；演示适配不属于本库依赖。

来源快照见 [import-manifest](../../../docs/import-manifest.json)；当前分类见 [library-catalog](../../../docs/library-catalog.json)；逐项验证及未验证边界见 [固定测试报告](../../../doc/testing/TEST_REPORT.md)。本页命令是复核入口，不代表本轮已重新执行。源码迁入、构建和 JVM 测试不等于真机、服务器、视觉或生产验收。

[返回项目说明](../../../README.md) · [库目录](../../README.md)
