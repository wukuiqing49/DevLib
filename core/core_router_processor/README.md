# core_router_processor

KSP 路由代码生成。来源：AndroidCoreRouters。类型：JVM JAR / Gradle plugin。

## 接入

```groovy
ksp project(':core:core_router_processor')
```

独立发布坐标：`com.wkq.dev:core-router-processor:0.1.0-SNAPSHOT`。运行 `:core:core_router_processor:publishReleasePublicationToDevLibLocalRepository` 后，在消费工程声明 DevLib 的 `build/repo` 文件仓库；不要同时引入原库的 AAR。

## 宿主责任与边界

通过 ksp project 接入，设置唯一 moduleName。

## 验证状态

来源与初始文件摘要见根目录 `docs/import-manifest.json`。迁入后验证统一记录在 `doc/testing/TEST_REPORT.md`；有源码或产物不等于完成真机、跨平台或商用验收。原宿主尚未回接，不宣称迁移闭环完成。
