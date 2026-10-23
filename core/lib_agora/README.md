# lib_agora

Agora RTC 媒体适配。来源：Starxen。类型：Android Library / AAR。

## 接入

```groovy
implementation project(':core:lib_agora')
```

独立发布坐标：`com.wkq.dev:lib-agora:0.1.0-SNAPSHOT`。运行 `:core:lib_agora:publishReleasePublicationToDevLibLocalRepository` 后，在消费工程声明 DevLib 的 `build/repo` 文件仓库；不要同时引入原库的 AAR。

## 宿主责任与边界

宿主提供 App ID、token 与生命周期，不迁入商业服务端。

## 验证状态

来源与初始文件摘要见根目录 `docs/import-manifest.json`。迁入后验证统一记录在 `doc/testing/TEST_REPORT.md`；有源码或产物不等于完成真机、跨平台或商用验收。原宿主尚未回接，不宣称迁移闭环完成。

更多原始 API 说明见 [README.upstream.md](README.upstream.md)。
