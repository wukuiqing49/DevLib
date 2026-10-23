# core_utils

通用文件、存储、Coil、MMKV 与设备工具。来源：AndroidCoreUtils。类型：Android Library / AAR。

## 接入

```groovy
implementation project(':core:core_utils')
```

独立发布坐标：`com.wkq.dev:core-utils:0.1.0-SNAPSHOT`。运行 `:core:core_utils:publishReleasePublicationToDevLibLocalRepository` 后，在消费工程声明 DevLib 的 `build/repo` 文件仓库；不要同时引入原库的 AAR。

## 宿主责任与边界

宿主调用 CoreUtils.init；不自动启用崩溃采集。

## 验证状态

来源与初始文件摘要见根目录 `docs/import-manifest.json`。迁入后验证统一记录在 `doc/testing/TEST_REPORT.md`；有源码或产物不等于完成真机、跨平台或商用验收。原宿主尚未回接，不宣称迁移闭环完成。
