# core_router_gradle_plugin

路由 Gradle 配置插件。来源：AndroidCoreRouters。类型：JVM JAR / Gradle plugin。

## 接入

```groovy
plugins {
    id 'com.wkq.router' version '0.1.0-SNAPSHOT'
}
wkqRouter {
    moduleName = 'your_unique_module'
}
```

独立发布坐标：`com.wkq.dev:core-router-gradle-plugin:0.1.0-SNAPSHOT`。运行 `:core:core_router_gradle_plugin:publishReleasePublicationToDevLibLocalRepository` 后，在消费工程声明 DevLib 的 `build/repo` 文件仓库；不要同时引入原库的 AAR。

## 宿主责任与边界

插件默认使用 DevLib 发布坐标；同仓演示直接配置 KSP，不从主构建应用尚未发布的插件。

## 验证状态

来源与初始文件摘要见根目录 `docs/import-manifest.json`。迁入后验证统一记录在 `doc/testing/TEST_REPORT.md`；有源码或产物不等于完成真机、跨平台或商用验收。原宿主尚未回接，不宣称迁移闭环完成。

## 插件解析

先从 DevLib 根工程发布本地库，在消费工程 settings.gradle 的 pluginManagement.repositories 与 dependencyResolutionManagement.repositories 都配置发布仓库。插件 marker 和带 POM 的依赖必须可解析，普通 implementation 不能应用 Gradle 插件。完整可编译示例见 samples/published-consumer；同仓主工程继续直接配置 KSP，不在同一构建中应用尚未发布的插件。
