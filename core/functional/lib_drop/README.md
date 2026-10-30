# lib_drop

局域网只读文件分享、配对审批、HTTP/HTTPS/Range 和 WebSocket 传输。

## 定位与依赖

- 分类：独立功能库；来源：DropLAN。
- 物理目录：`core/functional/lib_drop`；Gradle 路径：`:core:lib_drop`。
- Android AAR；模块声明 minSdk 26，compileSdk 37；JVM target 17。这是模块声明值，实际消费下限还受传递依赖与 Manifest 合并约束。
- 本仓模块依赖：无其他 core 模块依赖。

## 主要入口与调用流程

[ShareEngine](src/main/java/com/wkq/droplan/drop/ShareEngine.kt)、[ShareSource](src/main/java/com/wkq/droplan/drop/ShareSource.kt)、[DropServer](src/main/java/com/wkq/droplan/drop/DropServer.kt)、[TransferQueue](src/main/java/com/wkq/droplan/drop/TransferQueue.kt)、[TlsIdentity](src/main/java/com/wkq/droplan/drop/TlsIdentity.kt)。

授权选取文件源 → 创建分享会话并显示地址 → 用户审批配对/下载 → 退出或网络变化时结束分享。

不是 NAS 文件管理器；宿主负责局域网权限、证书、前台/后台生命周期与用户确认。浏览器接收页属于 Demo，不在库中绑定原产品网页。

## 接入

消费模块 `dependencies` 中添加：

```groovy
implementation project(':core:lib_drop')
```

## 可靠性与接入边界（2026-10-27）

结束分享时，即使单个流关闭回调失败，也会继续撤销其余传输、停止监听及清理线程池，最后报告聚合错误。关闭操作可重复调用。

浏览器测试夹具加载 feature_demo_files 中现有 demo_receiver，不依赖已移出的 lib_html。宿主仍需在网络切换、页面/服务退出时结束对应分享，并管理证书、用户审批和权限；Range 重试不等于持久后台传输。

[上游 API 参考](README.upstream.md) 为保留的历史文档，其中旧路径、坐标和发布命令不作为 DevLib 当前配置。

## 产物发布与本地检查

标准 Maven 坐标：`com.wkq.dev:lib-drop:0.1.0-SNAPSHOT`。默认版本统一来自根 `devlibVersion`；网络库另支持发布版本覆盖。不要同时依赖同库源码模块与旧 Maven AAR/JAR。

以下命令在 DevLib 根目录执行：

```powershell
.\gradlew.bat :core:lib_drop:assembleDebug :core:lib_drop:testDebugUnitTest
.\gradlew.bat :core:lib_drop:publishReleasePublicationToDevLibLocalRepository
```

本地仓库位于根 `build/repo`。有本仓模块依赖时，先运行根 `publishLibrariesToLocalRepository` 发布完整依赖和插件 marker；不要只复制一个 AAR/JAR。消费工程添加对应文件 Maven 仓库，再按标准坐标接入。

## 工作台与验证状态

实际调用示例：[DropActivity](../../../feature/feature_demo_files/src/main/java/com/wkq/dev/demo/files/DropActivity.kt)。入口由工作台对应分类进入；演示适配不属于本库依赖。

来源快照见 [import-manifest](../../../docs/import-manifest.json)；当前分类见 [library-catalog](../../../docs/library-catalog.json)；逐项验证及未验证边界见 [固定测试报告](../../../doc/testing/TEST_REPORT.md)。本页命令是复核入口，不代表本轮已重新执行。源码迁入、构建和 JVM 测试不等于真机、服务器、视觉或生产验收。

[返回项目说明](../../../README.md) · [库目录](../../README.md)
