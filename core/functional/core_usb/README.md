# core_usb

通过 Android SAF 访问用户授权的文档树和可移动存储。

## 定位与依赖

- 分类：独立功能库；来源：NASFileManager。
- 物理目录：`core/functional/core_usb`；Gradle 路径：`:core:core_usb`。
- Android AAR；模块声明 minSdk 26，compileSdk 37；JVM target 17。这是模块声明值，实际消费下限还受传递依赖与 Manifest 合并约束。
- 本仓模块依赖：无其他 core 模块依赖。

## 主要入口与调用流程

[UsbStorage](src/main/java/com/wkq/usb/UsbStorage.kt)。

宿主通过系统目录选择器获取 tree Uri → 按需持久化授权 → 创建 UsbStorage 并后台读写 → 响应拔出或授权撤销。

不是 USB 驱动，不实现 NTFS/exFAT 文件系统；仅能访问 SAF 提供方授权内容。复制后删除不等于原子移动，清理失败需提示和核对。

## 接入

消费模块 `dependencies` 中添加：

```groovy
implementation project(':core:core_usb')
```

## 可靠性与接入边界（2026-10-27）

新建写入失败或取消时，尽力删除本次 createDocument 返回的 URI；只清理能证明归属于本次操作的文档。清理失败以 suppressed 异常附加到原错误，不覆盖取消或源读取错误。设备拔出或权限撤销可能导致清理失败，宿主需要提示并提供后续核对。

moveNew 在创建和删除源文件前检查取消，是复制后删除，不保证原子移动；源删除失败时尽力清理新目标。磁盘空间、SAF 提供方与授权状态仍由宿主管理，库不新增文件系统驱动。

[上游 API 参考](README.upstream.md) 为保留的历史文档，其中旧路径、坐标和发布命令不作为 DevLib 当前配置。

## 产物发布与本地检查

标准 Maven 坐标：`com.wkq.dev:core-usb:0.1.0-SNAPSHOT`。默认版本统一来自根 `devlibVersion`；网络库另支持发布版本覆盖。不要同时依赖同库源码模块与旧 Maven AAR/JAR。

以下命令在 DevLib 根目录执行：

```powershell
.\gradlew.bat :core:core_usb:assembleDebug :core:core_usb:testDebugUnitTest
.\gradlew.bat :core:core_usb:publishReleasePublicationToDevLibLocalRepository
```

本地仓库位于根 `build/repo`。有本仓模块依赖时，先运行根 `publishLibrariesToLocalRepository` 发布完整依赖和插件 marker；不要只复制一个 AAR/JAR。消费工程添加对应文件 Maven 仓库，再按标准坐标接入。

## 工作台与验证状态

实际调用示例：[UsbActivity](../../../feature/feature_demo_files/src/main/java/com/wkq/dev/demo/files/UsbActivity.kt)。入口由工作台对应分类进入；演示适配不属于本库依赖。

来源快照见 [import-manifest](../../../docs/import-manifest.json)；当前分类见 [library-catalog](../../../docs/library-catalog.json)；逐项验证及未验证边界见 [固定测试报告](../../../doc/testing/TEST_REPORT.md)。本页命令是复核入口，不代表本轮已重新执行。源码迁入、构建和 JVM 测试不等于真机、服务器、视觉或生产验收。

[返回项目说明](../../../README.md) · [库目录](../../README.md)
