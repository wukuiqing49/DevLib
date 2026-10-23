# RFB 美颜方案

本库通过 `api` 依赖 `lib_beauty`，内部使用 ONNX Runtime。共享美颜不反向依赖 RFB；宿主按需选择本库或独立 MediaPipe 后端。

```kotlin
val beauty = RfbBeauty.createSession(
    context = applicationContext,
    analysisIntervalNs = 100_000_000L,
    onError = { error -> /* 错误可能来自后台分析线程 */ },
)
beauty.settings = BeautySettings(smoothing = .4f, whitening = .3f)
beauty.start()
val output = beauty.process(inputFrame)
// 同一视频线程停止、关闭；输出交回直播引擎。
beauty.stop()
beauty.close()
```

默认本地 `version-RFB-320.onnx` 仅输出人脸框，不提供眼镜、胡子所需关键点；磨皮、美白、色调仍可独立工作。

## 可选关键点

`RfbBeauty.createSession` 的 `refinerFactory` 每次在分析线程构建新的 `FaceLandmarkRefiner`。返回对象的所有权交给 RFB 检测器，宿主不要重复释放，也不要让多个会话共享同一个实例。

`OnnxFivePointRefiner` 接受自行提供的模型字节，要求 float32 RGB NCHW `[1,3,size,size]`，输出 float32 `[1,10]`，眼1、眼2、鼻、嘴角1、嘴角2的 xy；均值、标准差和归一化范围必须与训练模型一致。适配器严格验证形状和类型，不能直接装载 SeetaFace `.csta`。

五点只能支持粗定位眼镜，不包含上唇，胡子会保持隐藏；不得用嘴角估算结果冒充精确上唇。仓库没有捆绑五点权重，也未声明所有模型商业再分发许可已完成核验。模型来源和哈希见 `doc/third-party/MODELS.md`。

检测器、关键点适配器的创建/推理/关闭必须在同一线程；`BeautySession` 自动遵守该约定。关闭幂等，关闭后推理、跨线程操作会明确报错，共享 ORT 环境不会由单个检测器销毁。
