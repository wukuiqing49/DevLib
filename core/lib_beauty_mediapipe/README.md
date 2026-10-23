# lib_beauty_mediapipe

MediaPipe 人脸分析适配。来源：BeautySDK。类型：Android Library / AAR。

## 接入

```groovy
implementation project(':core:lib_beauty_mediapipe')
```

独立发布坐标：`com.wkq.dev:lib-beauty-mediapipe:0.1.0-SNAPSHOT`。运行 `:core:lib_beauty_mediapipe:publishReleasePublicationToDevLibLocalRepository` 后，在消费工程声明 DevLib 的 `build/repo` 文件仓库；不要同时引入原库的 AAR。

## 宿主责任与边界

可选后端；宿主提供模型，不与 RFB 强制绑定。

## 验证状态

来源与初始文件摘要见根目录 `docs/import-manifest.json`。迁入后验证统一记录在 `doc/testing/TEST_REPORT.md`；有源码或产物不等于完成真机、跨平台或商用验收。原宿主尚未回接，不宣称迁移闭环完成。
