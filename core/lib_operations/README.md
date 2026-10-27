# lib_operations

图片操作预算、取消、结果、进度及原子输出契约。来源：PhotoKit。类型：Android Library / AAR。

## 接入

```groovy
implementation project(':core:lib_operations')
```

独立发布坐标：`com.wkq.dev:lib-operations:0.1.0-SNAPSHOT`。运行 `:core:lib_operations:publishReleasePublicationToDevLibLocalRepository` 后，在消费工程声明 DevLib 的 `build/repo` 文件仓库；不要同时引入原库的 AAR。

## 宿主责任与边界

独立契约供图像、二维码、PDF 等库共享，不依赖 feature 或计费。

## 验证状态

来源与初始文件摘要见根目录 `docs/import-manifest.json`。迁入后验证统一记录在 `doc/testing/TEST_REPORT.md`；有源码或产物不等于完成真机、跨平台或商用验收。原宿主尚未回接，不宣称迁移闭环完成。

## 可靠性与接入边界（2026-10-27）

显式 OperationCancellation 或线程中断映射为 CANCELLED；java.util.concurrent.CancellationException（包含协程取消）保持原异常向上传播，不包装为 INTERNAL_ERROR。页数预算同时拒绝负数，避免溢出绕过。

契约是同步、协作式取消，不能强行终止第三方阻塞调用。进度回调应短小且不抛错；输出提交后才收到取消或回调异常时，宿主仍需核对实际产物。内部支撑无需单独业务页面。
