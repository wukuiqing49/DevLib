# lib_beauty

美颜帧处理、渲染及会话契约。来源：BeautySDK。类型：Android Library / AAR。

## 接入

```groovy
implementation project(':core:lib_beauty')
```

独立发布坐标：`com.wkq.dev:lib-beauty:0.1.0-SNAPSHOT`。运行 `:core:lib_beauty:publishReleasePublicationToDevLibLocalRepository` 后，在消费工程声明 DevLib 的 `build/repo` 文件仓库；不要同时引入原库的 AAR。

## 宿主责任与边界

实验能力，保留线程与帧所有权约束，不声明商用美颜验收。

## 验证状态

来源与初始文件摘要见根目录 `docs/import-manifest.json`。迁入后验证统一记录在 `doc/testing/TEST_REPORT.md`；有源码或产物不等于完成真机、跨平台或商用验收。原宿主尚未回接，不宣称迁移闭环完成。

更多原始 API 说明见 [README.upstream.md](README.upstream.md)。
