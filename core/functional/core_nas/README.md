# core_nas

SMB、WebDAV、SFTP 独立连接会话、目录浏览及流式读写。

## 定位与依赖

- 分类：独立功能库；来源：NASFileManager。
- 物理目录：`core/functional/core_nas`；Gradle 路径：`:core:core_nas`。
- Android AAR；模块声明 minSdk 26，compileSdk 37；JVM target 17。这是模块声明值，实际消费下限还受传递依赖与 Manifest 合并约束。
- 本仓模块依赖：无其他 core 模块依赖。

## 主要入口与调用流程

[NasClient](src/main/java/com/wkq/naslib/NasClient.kt)、[NasStorage](src/main/java/com/wkq/naslib/NasStorage.kt)、[NasDiscovery](src/main/java/com/wkq/naslib/NasDiscovery.kt)。

配置 NasConnection → NasClient.open 创建会话 → 在后台执行目录/流操作 → 关闭流和会话。

阻塞 IO 不应放主线程；同一会话串行使用。宿主维护账号、SFTP 主机指纹、WebDAV 证书信任、部分写入核对及后台调度。

## 接入

消费模块 `dependencies` 中添加：

```groovy
implementation project(':core:core_nas')
```

## 可靠性与接入边界（2026-10-27）

WebDAV 禁止自动重放网络请求，重定向保持关闭。复制每块检查线程中断；协程取消、宿主回调异常和中断不降级为普通可重试协议错误。

会话及返回流由调用者关闭，同一会话串行使用。新建写入失败可能已经在服务端留下部分文件，不能按文件名盲删或自动重试；应记录操作归属并核对后清理。账号、可信主机指纹、空间策略及后台任务由宿主提供。真实固件、网络切换和 Android 17 权限运行期表现尚未验收。

[上游 API 参考](README.upstream.md) 为保留的历史文档，其中旧路径、坐标和发布命令不作为 DevLib 当前配置。

## 产物发布与本地检查

标准 Maven 坐标：`com.wkq.dev:core-nas:0.1.0-SNAPSHOT`。默认版本统一来自根 `devlibVersion`；网络库另支持发布版本覆盖。不要同时依赖同库源码模块与旧 Maven AAR/JAR。

以下命令在 DevLib 根目录执行：

```powershell
.\gradlew.bat :core:core_nas:assembleDebug :core:core_nas:testDebugUnitTest
.\gradlew.bat :core:core_nas:publishReleasePublicationToDevLibLocalRepository
```

本地仓库位于根 `build/repo`。有本仓模块依赖时，先运行根 `publishLibrariesToLocalRepository` 发布完整依赖和插件 marker；不要只复制一个 AAR/JAR。消费工程添加对应文件 Maven 仓库，再按标准坐标接入。

## 工作台与验证状态

实际调用示例：[NasActivity](../../../feature/feature_demo_files/src/main/java/com/wkq/dev/demo/files/NasActivity.kt)。入口由工作台对应分类进入；演示适配不属于本库依赖。

来源快照见 [import-manifest](../../../docs/import-manifest.json)；当前分类见 [library-catalog](../../../docs/library-catalog.json)；逐项验证及未验证边界见 [固定测试报告](../../../doc/testing/TEST_REPORT.md)。本页命令是复核入口，不代表本轮已重新执行。源码迁入、构建和 JVM 测试不等于真机、服务器、视觉或生产验收。

[返回项目说明](../../../README.md) · [库目录](../../README.md)
