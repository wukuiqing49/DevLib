# portrait-model-assets

PhotoKit 可选人像模型 Play Asset Pack。它不是 Library/AAR，不参与独立 Maven 发布。

在宿主 app 中配置：`android { assetPacks = [':core:portrait-model-assets'] }`。

## 宿主责任与边界

这是 Play Asset Pack，不是独立 AAR；在宿主 app 的 assetPacks 中显式声明。

## 验证状态

来源与初始文件摘要见根目录 `docs/import-manifest.json`。迁入后验证统一记录在 `doc/testing/TEST_REPORT.md`；有源码或产物不等于完成真机、跨平台或商用验收。原宿主尚未回接，不宣称迁移闭环完成。

更多原始 API 说明见 [README.upstream.md](README.upstream.md)。
