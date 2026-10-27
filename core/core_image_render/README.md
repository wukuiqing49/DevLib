# core_image_render

图片排版与渲染。来源：PhotoKit。类型：Android Library / AAR。

## 接入

```groovy
implementation project(':core:core_image_render')
```

独立发布坐标：`com.wkq.dev:core-image-render:0.1.0-SNAPSHOT`。运行 `:core:core_image_render:publishReleasePublicationToDevLibLocalRepository` 后，在消费工程声明 DevLib 的 `build/repo` 文件仓库；不要同时引入原库的 AAR。

## 宿主责任与边界

公开操作契约通过 lib_operations 传递。

## 验证状态

来源与初始文件摘要见根目录 `docs/import-manifest.json`。迁入后验证统一记录在 `doc/testing/TEST_REPORT.md`；有源码或产物不等于完成真机、跨平台或商用验收。原宿主尚未回接，不宣称迁移闭环完成。

## 可靠性与接入边界（2026-10-27）

解码预算同时检查最终尺寸和实际采样中间位图，避免目标缩小但解码峰值超限。旋转、缩放、可变副本或进度回调失败时释放本次拥有的 Bitmap；成功返回的 Bitmap 由调用者 recycle。

EXIF 方向处理沿用既有实现；像素预算不等于整个进程内存上限，宿主应按设备能力配置预算并限制并发。标注、水印、样机、相框和签名处理仍共用现有引擎。
