# core_pdf

图片与页面 PDF 输出。来源：PhotoKit。类型：Android Library / AAR。

## 接入

```groovy
implementation project(':core:core_pdf')
```

独立发布坐标：`com.wkq.dev:core-pdf:0.1.0-SNAPSHOT`。运行 `:core:core_pdf:publishReleasePublicationToDevLibLocalRepository` 后，在消费工程声明 DevLib 的 `build/repo` 文件仓库；不要同时引入原库的 AAR。

## 宿主责任与边界

保留操作预算与取消边界。
公开请求使用的 `ImageSource` 由 `api` 传递 core_image_render；宿主无需为了构造请求额外补该模块。

## 验证状态

来源与初始文件摘要见根目录 `docs/import-manifest.json`。迁入后验证统一记录在 `doc/testing/TEST_REPORT.md`；有源码或产物不等于完成真机、跨平台或商用验收。原宿主尚未回接，不宣称迁移闭环完成。

## 可靠性与接入边界（2026-10-27）

页面、网格边距与间隔先校验范围，避免极端整数参数溢出。逐图释放 Bitmap，文件输出复用 lib_operations 的原子写入；URI/PFD 由提供方写入，不承诺回滚已写入内容，宿主应先输出临时文件再显式发布重要文档。

PDF 为图片与页面输出能力，不提供阅读器、文本编辑或 Office 转换。字体、中文长文案、分页视觉与实际阅读器兼容性仍需产物验收。
