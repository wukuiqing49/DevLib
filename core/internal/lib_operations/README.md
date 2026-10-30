# lib_operations

操作预算、协作式取消、结果、进度及原子输出的内部公共契约。

## 定位与依赖

- 分类：内部支撑库；来源：PhotoKit。
- 物理目录：`core/internal/lib_operations`；Gradle 路径：`:core:lib_operations`。
- Android AAR；模块声明 minSdk 24，compileSdk 37；JVM target 17。这是模块声明值，实际消费下限还受传递依赖与 Manifest 合并约束。
- 本仓模块依赖：无其他 core 模块依赖。

## 主要入口与调用流程

[OperationContracts](src/main/java/com/wkq/photo/core/operation/OperationContracts.kt)、[AtomicFileOutput](src/main/java/com/wkq/photo/core/utils/AtomicFileOutput.kt)、[OutputPathRegistry](src/main/java/com/wkq/photo/core/operation/OutputPathRegistry.kt)、[TemporaryFileCleaner](src/main/java/com/wkq/photo/core/utils/TemporaryFileCleaner.kt)。

由压缩、渲染、二维码和文档库复用操作契约 → 宿主提供预算/取消/进度 → 在输出提交后处理结果。

内部支撑，不要求单独业务页面；不承载业务调度、数据库或付费逻辑。协作取消不能强行终止第三方阻塞调用。

## 接入

消费模块 `dependencies` 中添加：

```groovy
implementation project(':core:lib_operations')
```

## 可靠性与接入边界（2026-10-27）

显式 OperationCancellation 或线程中断映射为 CANCELLED；java.util.concurrent.CancellationException（包含协程取消）保持原异常向上传播，不包装为 INTERNAL_ERROR。页数预算同时拒绝负数，避免溢出绕过。

契约是同步、协作式取消，不能强行终止第三方阻塞调用。进度回调应短小且不抛错；输出提交后才收到取消或回调异常时，宿主仍需核对实际产物。内部支撑无需单独业务页面。

## 产物发布与本地检查

标准 Maven 坐标：`com.wkq.dev:lib-operations:0.1.0-SNAPSHOT`。默认版本统一来自根 `devlibVersion`；网络库另支持发布版本覆盖。不要同时依赖同库源码模块与旧 Maven AAR/JAR。

以下命令在 DevLib 根目录执行：

```powershell
.\gradlew.bat :core:lib_operations:assembleDebug :core:lib_operations:testDebugUnitTest
.\gradlew.bat :core:lib_operations:publishReleasePublicationToDevLibLocalRepository
```

本地仓库位于根 `build/repo`。有本仓模块依赖时，先运行根 `publishLibrariesToLocalRepository` 发布完整依赖和插件 marker；不要只复制一个 AAR/JAR。消费工程添加对应文件 Maven 仓库，再按标准坐标接入。

## 工作台与验证状态

没有独立业务页面；通过压缩、渲染、二维码和文档流程集成验证。

来源快照见 [import-manifest](../../../docs/import-manifest.json)；当前分类见 [library-catalog](../../../docs/library-catalog.json)；逐项验证及未验证边界见 [固定测试报告](../../../doc/testing/TEST_REPORT.md)。本页命令是复核入口，不代表本轮已重新执行。源码迁入、构建和 JVM 测试不等于真机、服务器、视觉或生产验收。

[返回项目说明](../../../README.md) · [库目录](../../README.md)
