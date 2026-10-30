# core_network

Retrofit、OkHttp、统一网络响应，以及上传进度和下载封装。

## 定位与依赖

- 分类：架构基础库；来源：AndroidCoreNetwork。
- 物理目录：`core/architecture/core_network`；Gradle 路径：`:core:core_network`。
- Android AAR；模块声明 minSdk 23，compileSdk 37；JVM target 17。这是模块声明值，实际消费下限还受传递依赖与 Manifest 合并约束。
- 本仓模块依赖：无其他 core 模块依赖。

## 主要入口与调用流程

[NetConfig](src/main/java/com/wkq/net/config/NetConfig.kt)、[NetManager](src/main/java/com/wkq/net/core/NetManager.kt)、[ApiRetrofit](src/main/java/com/wkq/net/core/ApiRetrofit.kt)、[UploadService](src/main/java/com/wkq/net/UploadService.kt)、[UploadFlow](src/main/java/com/wkq/net/core/UploadFlow.kt)、[DownloadRetrofit](src/main/java/com/wkq/net/core/DownloadRetrofit.kt)。

配置服务地址、请求头与响应解析 → 声明业务 Retrofit 接口 → 在宿主协程中发起请求并展示错误/进度。

宿主提供认证、接口协议、HTTPS 信任与重试策略；它负责网络调用，FTP/FTPS 上传使用 lib_upload，NAS 协议使用 core_nas。

## 接入

消费模块 `dependencies` 中添加：

```groovy
implementation project(':core:core_network')
```

远端发布参数、凭据与独立版本覆盖见 [网络库发布指南](docs/core_network_publish.md)。目前通用远端发布配置仅扩展此模块，其他库默认本地发布。

## 产物发布与本地检查

标准 Maven 坐标：`com.wkq.dev:core-network:0.1.0-SNAPSHOT`。默认版本统一来自根 `devlibVersion`；网络库另支持发布版本覆盖。不要同时依赖同库源码模块与旧 Maven AAR/JAR。

以下命令在 DevLib 根目录执行：

```powershell
.\gradlew.bat :core:core_network:assembleDebug :core:core_network:testDebugUnitTest
.\gradlew.bat :core:core_network:publishReleasePublicationToDevLibLocalRepository
```

本地仓库位于根 `build/repo`。有本仓模块依赖时，先运行根 `publishLibrariesToLocalRepository` 发布完整依赖和插件 marker；不要只复制一个 AAR/JAR。消费工程添加对应文件 Maven 仓库，再按标准坐标接入。

## 工作台与验证状态

实际调用示例：[NetworkActivity](../../../feature/feature_demo_foundation/src/main/java/com/wkq/dev/demo/foundation/NetworkActivity.kt)。入口由工作台对应分类进入；演示适配不属于本库依赖。

来源快照见 [import-manifest](../../../docs/import-manifest.json)；当前分类见 [library-catalog](../../../docs/library-catalog.json)；逐项验证及未验证边界见 [固定测试报告](../../../doc/testing/TEST_REPORT.md)。本页命令是复核入口，不代表本轮已重新执行。源码迁入、构建和 JVM 测试不等于真机、服务器、视觉或生产验收。

[返回项目说明](../../../README.md) · [库目录](../../README.md)
