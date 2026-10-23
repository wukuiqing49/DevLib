# phototoolkit-raw

RAW 解码与图片引擎适配。来源：PhotoKit。类型：Android Library / AAR。

## 接入

```groovy
implementation project(':core:phototoolkit-raw')
```

独立发布坐标：`com.wkq.dev:phototoolkit-raw:0.1.0-SNAPSHOT`。运行 `:core:phototoolkit-raw:publishReleasePublicationToDevLibLocalRepository` 后，在消费工程声明 DevLib 的 `build/repo` 文件仓库；不要同时引入原库的 AAR。

## 宿主责任与边界

依赖图片引擎及 Native 运行库，不重复封装 UI。

## 验证状态

来源与初始文件摘要见根目录 `docs/import-manifest.json`。迁入后验证统一记录在 `doc/testing/TEST_REPORT.md`；有源码或产物不等于完成真机、跨平台或商用验收。原宿主尚未回接，不宣称迁移闭环完成。
