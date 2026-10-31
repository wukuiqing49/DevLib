# core_utils

文件、Uri、选择器、图片加载、存储、设备与系统工具。

## 定位与依赖

- 分类：架构基础库；来源：AndroidCoreUtils。
- 物理目录：`core/architecture/core_utils`；Gradle 路径：`:core:core_utils`。
- Android AAR；模块声明 minSdk 23，compileSdk 37；JVM target 17。这是模块声明值，实际消费下限还受传递依赖与 Manifest 合并约束。
- 本仓模块依赖：无其他 core 模块依赖。

## 主要入口与调用流程

[CoreUtils](src/main/java/com/wkq/util/CoreUtils.kt)、[FileUtil](src/main/java/com/wkq/util/FileUtil.kt)、[UriUtil](src/main/java/com/wkq/util/UriUtil.kt)、[ShareUtil](src/main/java/com/wkq/util/ShareUtil.kt)、[ImageLoaderUtil](src/main/java/com/wkq/util/coil/ImageLoaderUtil.kt)、[PermissionHelper](src/main/java/com/wkq/util/PermissionHelper.kt)。

由 Application 调用 CoreUtils.init 一次 → 使用对应 object/扩展函数 → 在宿主生命周期处理选择器和权限结果。

宿主配置 FileProvider、分享授权与权限 UI；不自动启用崩溃采集，不重复初始化 MMKV 等全局组件。

## 接入

消费模块 `dependencies` 中添加：

```groovy
implementation project(':core:core_utils')
```

## 产物发布与本地检查

标准 Maven 坐标：`com.wkq.dev:core-utils:1.1.2`。本库版本来自模块 `version.properties` 的 `VERSION_NAME`；发布配置同步项目版本、POM 与 Gradle 元数据。不要同时依赖同库源码模块与旧 Maven AAR/JAR。

以下命令在 DevLib 根目录执行：

```powershell
.\gradlew.bat :core:core_utils:assembleDebug :core:core_utils:testDebugUnitTest
.\gradlew.bat :core:core_utils:publishReleasePublicationToDevLibLocalRepository
```

本地仓库位于根 `build/repo`。有本仓模块依赖时，先运行根 `publishLibrariesToLocalRepository` 发布完整依赖和插件 marker；不要只复制一个 AAR/JAR。消费工程添加对应文件 Maven 仓库，再按标准坐标接入。

## 工作台与验证状态

实际调用示例：[UtilsActivity](../../../feature/feature_demo_foundation/src/main/java/com/wkq/dev/demo/foundation/UtilsActivity.kt)。入口由工作台对应分类进入；演示适配不属于本库依赖。

来源快照见 [import-manifest](../../../docs/import-manifest.json)；当前分类见 [library-catalog](../../../docs/library-catalog.json)；逐项验证及未验证边界见 [固定测试报告](../../../doc/testing/TEST_REPORT.md)。本页命令是复核入口，不代表本轮已重新执行。源码迁入、构建和 JVM 测试不等于真机、服务器、视觉或生产验收。

[返回项目说明](../../../README.md) · [库目录](../../README.md)

## 1.1.2 更新说明

- 沿原仓库最新标签版本 `1.1.1` 将补丁号加 1；InsetsUtil 沿用 AndroidX 兼容边到边入口。
- 保留公开包名及 API；使用 DevLib 坐标 `com.wkq.dev:core-utils:1.1.2`，不代表原仓库 `com.github...` 坐标或标签已发布。
- 发布前核对 AAR、sources、POM 与 module metadata；构建和本地发布不代表设备、视觉或远端发布验收。
- 协作规范见根 [AGENTS.md](../../../AGENTS.md) 与 [.agents 使用说明](../../../.agents/README.md)，无需手动输入 Skill 名称。
