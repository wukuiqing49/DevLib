# core_router_gradle_plugin

配置路由运行时、KSP 处理器依赖与 moduleName 的 Gradle 插件。

## 定位与依赖

- 分类：架构基础库；来源：AndroidCoreRouters。
- 物理目录：`core/architecture/core_router_gradle_plugin`；Gradle 路径：`:core:core_router_gradle_plugin`。
- JVM JAR，JVM target 17（不声明 Android minSdk）
- 本仓模块依赖：无其他 core 模块依赖。

## 主要入口与调用流程

[RouterGradlePlugin](src/main/java/com/wkq/router/gradle/RouterGradlePlugin.kt)。

先发布插件及路由依赖 → 在消费工程配置插件仓库和依赖仓库 → 应用 com.wkq.router → 配置 wkqRouter。

不是运行时库；插件 marker 与实现 JAR 都需可解析。同仓 Demo 直接配置 KSP，不在主构建应用尚未发布的自身插件。

## 接入

先发布插件 marker 及路由依赖，在消费项目 settings.gradle 的 pluginManagement.repositories 和 dependencyResolutionManagement.repositories 中配置同一 Maven 仓库。

```groovy
plugins { id 'com.wkq.router' version '0.1.0-SNAPSHOT' }
wkqRouter { moduleName = 'FeatureExample' }
```

插件自动添加路由运行时与 KSP 处理器；可通过扩展的 version/group 设置发布版本与组名。同仓工程保持直接配置 KSP。完整消费示例见 [published-consumer](../../../samples/published-consumer/README.md)。

## 产物发布与本地检查

标准 Maven 坐标：`com.wkq.dev:core-router-gradle-plugin:0.1.0-SNAPSHOT`。默认版本统一来自根 `devlibVersion`；网络库另支持发布版本覆盖。不要同时依赖同库源码模块与旧 Maven AAR/JAR。

以下命令在 DevLib 根目录执行：

```powershell
.\gradlew.bat :core:core_router_gradle_plugin:jar :core:core_router_gradle_plugin:test
.\gradlew.bat :core:core_router_gradle_plugin:publishAllPublicationsToDevLibLocalRepository
```

本地仓库位于根 `build/repo`。有本仓模块依赖时，先运行根 `publishLibrariesToLocalRepository` 发布完整依赖和插件 marker；不要只复制一个 AAR/JAR。消费工程添加对应文件 Maven 仓库，再按标准坐标接入。

## 工作台与验证状态

实际调用示例：[PluginActivity](../../../feature/feature_demo_foundation/src/main/java/com/wkq/dev/demo/foundation/PluginActivity.kt)。入口由工作台对应分类进入；演示适配不属于本库依赖。

来源快照见 [import-manifest](../../../docs/import-manifest.json)；当前分类见 [library-catalog](../../../docs/library-catalog.json)；逐项验证及未验证边界见 [固定测试报告](../../../doc/testing/TEST_REPORT.md)。本页命令是复核入口，不代表本轮已重新执行。源码迁入、构建和 JVM 测试不等于真机、服务器、视觉或生产验收。

[返回项目说明](../../../README.md) · [库目录](../../README.md)
