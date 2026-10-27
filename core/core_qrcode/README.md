# core_qrcode

二维码生成与识别。来源：PhotoKit。类型：Android Library / AAR。

## 接入

```groovy
implementation project(':core:core_qrcode')
```

独立发布坐标：`com.wkq.dev:core-qrcode:0.1.0-SNAPSHOT`。运行 `:core:core_qrcode:publishReleasePublicationToDevLibLocalRepository` 后，在消费工程声明 DevLib 的 `build/repo` 文件仓库；不要同时引入原库的 AAR。

## 宿主责任与边界

封装现有 ZXing 能力，宿主处理相机授权。

## 验证状态

来源与初始文件摘要见根目录 `docs/import-manifest.json`。迁入后验证统一记录在 `doc/testing/TEST_REPORT.md`；有源码或产物不等于完成真机、跨平台或商用验收。原宿主尚未回接，不宣称迁移闭环完成。

## 可靠性与接入边界（2026-10-27）

识别多码前拒绝已回收 Bitmap，并限制像素副本为 1600 万像素；超大输入先由宿主采样。生成过程取消、样式回读失败或进度回调抛错时释放临时 Bitmap。实时扫描页面只交付一次结果，结束过程忽略重复回调。

生成保留原有尺寸预算、纠错及可扫码回读；不能保证所有相机、印刷尺寸、低对比度和遮挡条件均能识别。成功返回图片由调用者释放；相机授权、设备表现与视觉验收由宿主完成。
