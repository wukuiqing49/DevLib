# lib_live_api

直播媒体接口与状态契约。来源：Starxen。类型：Android Library / AAR。

## 接入

```groovy
implementation project(':core:lib_live_api')
```

独立发布坐标：`com.wkq.dev:lib-live-api:0.1.0-SNAPSHOT`。运行 `:core:lib_live_api:publishReleasePublicationToDevLibLocalRepository` 后，在消费工程声明 DevLib 的 `build/repo` 文件仓库；不要同时引入原库的 AAR。

## 宿主责任与边界

只迁入媒体契约，房间、账号、礼物等业务 UI 保留原项目。

## 验证状态

来源与初始文件摘要见根目录 `docs/import-manifest.json`。迁入后验证统一记录在 `doc/testing/TEST_REPORT.md`；有源码或产物不等于完成真机、跨平台或商用验收。原宿主尚未回接，不宣称迁移闭环完成。
