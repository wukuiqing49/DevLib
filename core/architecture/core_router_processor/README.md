# core_router_processor

KSP 路由扫描与注册/分组/参数代码生成器。

## 定位与依赖

- 分类：架构基础库；来源：AndroidCoreRouters。
- 物理目录：`core/architecture/core_router_processor`；Gradle 路径：`:core:core_router_processor`。
- JVM JAR，JVM target 17（不声明 Android minSdk）
- 本仓模块依赖：`implementation :core:core_router_annotation`

## 主要入口与调用流程

[RouteProcessor](src/main/java/com/wkq/router/processor/RouteProcessor.kt)、[RouteProcessorProvider](src/main/java/com/wkq/router/processor/RouteProcessorProvider.kt)。

对声明路由的模块应用 KSP → 以 ksp 依赖接入本模块 → 设置唯一合法 moduleName → 构建生成代码。

编译期 JVM 处理器，不应作为 implementation 运行时依赖。moduleName 满足 `[A-Za-z][A-Za-z0-9_]*`；各模块不能重复。

## 接入

消费模块 `dependencies` 中添加：

```groovy
// 宿主先应用与 Kotlin 版本匹配的 KSP 插件
ksp project(':core:core_router_processor')
// 以下配置在 dependencies 块外
// ksp { arg('moduleName', 'FeatureExample') }
```

路由整体接入同时需要运行时与生成器，参考 [同仓配置](../../../feature/feature_demo_foundation/build.gradle)；每个模块使用唯一的 `ksp { arg('moduleName', 'FeatureExample') }`。

## 产物发布与本地检查

标准 Maven 坐标：`com.wkq.dev:core-router-processor:0.1.0-SNAPSHOT`。默认版本统一来自根 `devlibVersion`；网络库另支持发布版本覆盖。不要同时依赖同库源码模块与旧 Maven AAR/JAR。

以下命令在 DevLib 根目录执行：

```powershell
.\gradlew.bat :core:core_router_processor:jar :core:core_router_processor:test
.\gradlew.bat :core:core_router_processor:publishReleasePublicationToDevLibLocalRepository
```

本地仓库位于根 `build/repo`。有本仓模块依赖时，先运行根 `publishLibrariesToLocalRepository` 发布完整依赖和插件 marker；不要只复制一个 AAR/JAR。消费工程添加对应文件 Maven 仓库，再按标准坐标接入。

## 工作台与验证状态

实际调用示例：[ProcessorActivity](../../../feature/feature_demo_foundation/src/main/java/com/wkq/dev/demo/foundation/ProcessorActivity.kt)。入口由工作台对应分类进入；演示适配不属于本库依赖。

来源快照见 [import-manifest](../../../docs/import-manifest.json)；当前分类见 [library-catalog](../../../docs/library-catalog.json)；逐项验证及未验证边界见 [固定测试报告](../../../doc/testing/TEST_REPORT.md)。本页命令是复核入口，不代表本轮已重新执行。源码迁入、构建和 JVM 测试不等于真机、服务器、视觉或生产验收。

[返回项目说明](../../../README.md) · [库目录](../../README.md)
