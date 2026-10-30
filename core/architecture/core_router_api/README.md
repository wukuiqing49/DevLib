# core_router_api

Android 路由运行时、参数传递、拦截器、服务与降级处理。

## 定位与依赖

- 分类：架构基础库；来源：AndroidCoreRouters。
- 物理目录：`core/architecture/core_router_api`；Gradle 路径：`:core:core_router_api`。
- Android AAR；模块声明 minSdk 23，compileSdk 37；JVM target 17。这是模块声明值，实际消费下限还受传递依赖与 Manifest 合并约束。
- 本仓模块依赖：`api :core:core_router_annotation`

## 主要入口与调用流程

[Router](src/main/java/com/wkq/router/api/Router.kt)、[Postcard](src/main/java/com/wkq/router/api/Postcard.kt)、[RouterConfig](src/main/java/com/wkq/router/api/RouterConfig.kt)、[RouterInitResult](src/main/java/com/wkq/router/api/RouterInitResult.kt)。

Application 调用 Router.init → 使用注解和 KSP 生成的路由注册 → 页面发起导航并处理失败/降级。

api 传递注解契约；宿主仍需接入 KSP 处理器和唯一 moduleName，不能只引入运行时就假定业务路由已注册。

## 接入

消费模块 `dependencies` 中添加：

```groovy
implementation project(':core:core_router_api')
```

路由整体接入同时需要运行时与生成器，参考 [同仓配置](../../../feature/feature_demo_foundation/build.gradle)；每个模块使用唯一的 `ksp { arg('moduleName', 'FeatureExample') }`。

## 产物发布与本地检查

标准 Maven 坐标：`com.wkq.dev:core-router-api:0.1.0-SNAPSHOT`。默认版本统一来自根 `devlibVersion`；网络库另支持发布版本覆盖。不要同时依赖同库源码模块与旧 Maven AAR/JAR。

以下命令在 DevLib 根目录执行：

```powershell
.\gradlew.bat :core:core_router_api:assembleDebug :core:core_router_api:testDebugUnitTest
.\gradlew.bat :core:core_router_api:publishReleasePublicationToDevLibLocalRepository
```

本地仓库位于根 `build/repo`。有本仓模块依赖时，先运行根 `publishLibrariesToLocalRepository` 发布完整依赖和插件 marker；不要只复制一个 AAR/JAR。消费工程添加对应文件 Maven 仓库，再按标准坐标接入。

## 工作台与验证状态

实际调用示例：[RouterActivity](../../../feature/feature_demo_foundation/src/main/java/com/wkq/dev/demo/foundation/RouterActivity.kt)。入口由工作台对应分类进入；演示适配不属于本库依赖。

来源快照见 [import-manifest](../../../docs/import-manifest.json)；当前分类见 [library-catalog](../../../docs/library-catalog.json)；逐项验证及未验证边界见 [固定测试报告](../../../doc/testing/TEST_REPORT.md)。本页命令是复核入口，不代表本轮已重新执行。源码迁入、构建和 JVM 测试不等于真机、服务器、视觉或生产验收。

[返回项目说明](../../../README.md) · [库目录](../../README.md)
