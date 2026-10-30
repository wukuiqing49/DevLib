# core_router_annotation

路由、参数、拦截器和服务注解及生成代码共享契约。

## 定位与依赖

- 分类：架构基础库；来源：AndroidCoreRouters。
- 物理目录：`core/architecture/core_router_annotation`；Gradle 路径：`:core:core_router_annotation`。
- JVM JAR，JVM target 17（不声明 Android minSdk）
- 本仓模块依赖：无其他 core 模块依赖。

## 主要入口与调用流程

[Route](src/main/java/com/wkq/router/annotation/Route.kt)、[Param](src/main/java/com/wkq/router/annotation/Param.kt)、[Interceptor](src/main/java/com/wkq/router/annotation/Interceptor.kt)、[ProvideService](src/main/java/com/wkq/router/annotation/ProvideService.kt)、[IRouteGroup](src/main/java/com/wkq/router/api/IRouteGroup.kt)、[IRouteInit](src/main/java/com/wkq/router/api/IRouteInit.kt)。

声明路由注解 → 配合 core_router_processor 生成注册代码 → 由 core_router_api 执行运行时导航。

纯 JVM 注解 JAR，不是 Android 页面或独立导航引擎；每个参与生成的模块需要唯一 moduleName。

## 接入

消费模块 `dependencies` 中添加：

```groovy
implementation project(':core:core_router_annotation')
```

路由整体接入同时需要运行时与生成器，参考 [同仓配置](../../../feature/feature_demo_foundation/build.gradle)；每个模块使用唯一的 `ksp { arg('moduleName', 'FeatureExample') }`。

## 产物发布与本地检查

标准 Maven 坐标：`com.wkq.dev:core-router-annotation:0.1.0-SNAPSHOT`。默认版本统一来自根 `devlibVersion`；网络库另支持发布版本覆盖。不要同时依赖同库源码模块与旧 Maven AAR/JAR。

以下命令在 DevLib 根目录执行：

```powershell
.\gradlew.bat :core:core_router_annotation:jar :core:core_router_annotation:test
.\gradlew.bat :core:core_router_annotation:publishReleasePublicationToDevLibLocalRepository
```

本地仓库位于根 `build/repo`。有本仓模块依赖时，先运行根 `publishLibrariesToLocalRepository` 发布完整依赖和插件 marker；不要只复制一个 AAR/JAR。消费工程添加对应文件 Maven 仓库，再按标准坐标接入。

## 工作台与验证状态

实际调用示例：[AnnotationActivity](../../../feature/feature_demo_foundation/src/main/java/com/wkq/dev/demo/foundation/AnnotationActivity.kt)。入口由工作台对应分类进入；演示适配不属于本库依赖。

来源快照见 [import-manifest](../../../docs/import-manifest.json)；当前分类见 [library-catalog](../../../docs/library-catalog.json)；逐项验证及未验证边界见 [固定测试报告](../../../doc/testing/TEST_REPORT.md)。本页命令是复核入口，不代表本轮已重新执行。源码迁入、构建和 JVM 测试不等于真机、服务器、视觉或生产验收。

[返回项目说明](../../../README.md) · [库目录](../../README.md)
