# lib_upload

FTP、FTPS、HTTP 上传客户端，以及配置、凭据与进度管理。

## 定位与依赖

- 分类：独立功能库；来源：PressIPTC。
- 物理目录：`core/functional/lib_upload`；Gradle 路径：`:core:lib_upload`。
- Android AAR；模块声明 minSdk 26，compileSdk 37；JVM target 17。这是模块声明值，实际消费下限还受传递依赖与 Manifest 合并约束。
- 本仓模块依赖：无其他 core 模块依赖。

## 主要入口与调用流程

[UploadClient](src/main/java/com/wkq/iptc/upload/UploadClient.kt)、[UploadDispatcher](src/main/java/com/wkq/iptc/upload/UploadDispatcher.kt)、[UploadServerConfig](src/main/java/com/wkq/iptc/upload/UploadServerConfig.kt)、[UploadServerProfile](src/main/java/com/wkq/iptc/upload/UploadServerProfile.kt)、[HttpUploader](src/main/java/com/wkq/iptc/upload/http/HttpUploader.kt)、[FtpsUploader](src/main/java/com/wkq/iptc/upload/FtpsUploader.kt)。

宿主配置服务器和协议 → 授权输入文件/Uri → 发起上传并观察进度/取消 → 核对远端结果。

SMB/SFTP/WebDAV 使用 core_nas；系统分享使用 core_utils。服务端可能已提交但响应丢失，不应盲目重放；不承诺字节级续传。

## 接入

消费模块 `dependencies` 中添加：

```groovy
implementation project(':core:lib_upload')
```

## 可靠性与接入边界（2026-10-27）

HTTP 上传关闭自动连接重试和重定向，POST/PUT 不由拦截器重放；仅无请求体的 GET/HEAD/OPTIONS 可有限重试 502/503/504。maxRetry 表示总尝试次数，范围 1..10。服务端可能已提交但客户端未收到响应，宿主须核对结果，不要盲目重新上传。

HTTP 协程取消会取消底层 Call；FTP/FTPS 使用 runInterruptible 并在流读取中检查中断，底层阻塞调用仍受协议超时约束。URI 请求体禁止自动重放并校验声明长度，响应体及时关闭。FTP 临时文件和不确定远端提交由宿主恢复处理；不承诺字节级可靠续传或所有协议原子提交。

## 产物发布与本地检查

标准 Maven 坐标：`com.wkq.dev:lib-upload:0.1.0-SNAPSHOT`。默认版本统一来自根 `devlibVersion`；网络库另支持发布版本覆盖。不要同时依赖同库源码模块与旧 Maven AAR/JAR。

以下命令在 DevLib 根目录执行：

```powershell
.\gradlew.bat :core:lib_upload:assembleDebug :core:lib_upload:testDebugUnitTest
.\gradlew.bat :core:lib_upload:publishReleasePublicationToDevLibLocalRepository
```

本地仓库位于根 `build/repo`。有本仓模块依赖时，先运行根 `publishLibrariesToLocalRepository` 发布完整依赖和插件 marker；不要只复制一个 AAR/JAR。消费工程添加对应文件 Maven 仓库，再按标准坐标接入。

## 工作台与验证状态

实际调用示例：[UploadActivity](../../../feature/feature_demo_files/src/main/java/com/wkq/dev/demo/files/UploadActivity.kt)。入口由工作台对应分类进入；演示适配不属于本库依赖。

来源快照见 [import-manifest](../../../docs/import-manifest.json)；当前分类见 [library-catalog](../../../docs/library-catalog.json)；逐项验证及未验证边界见 [固定测试报告](../../../doc/testing/TEST_REPORT.md)。本页命令是复核入口，不代表本轮已重新执行。源码迁入、构建和 JVM 测试不等于真机、服务器、视觉或生产验收。

[返回项目说明](../../../README.md) · [库目录](../../README.md)
