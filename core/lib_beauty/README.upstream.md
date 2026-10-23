# 共享美颜库

`lib_beauty` 提供统一帧 API、异步人脸分析调度、CPU/ES3 渲染，不依赖相机、直播引擎或宿主基础框架。

## 最小接入

```kotlin
// 一路视频使用一个会话；下面的所有生命周期和 process 调用在同一视频线程执行。
val beauty = BeautySession(
    factory = faceAnalyzerFactory, // 可为 null；基础磨皮、美白、色调无需人脸模型
    renderer = RgbaBeautyRenderer(),
    analysisIntervalNs = 100_000_000L, // 最多约每 100ms 尝试采样，不限制视频输出频率
    analysisMaxSide = 320,
    onError = { error -> /* 记录错误；此回调可能来自分析线程 */ },
)
beauty.settings = BeautySettings(smoothing = .4f, whitening = .3f, glasses = true, moustache = true, hat = true)
beauty.start()
// 引擎提供 RGBA8888；时间戳为同一单调时钟的纳秒值，逐帧严格递增。
val output = beauty.process(RgbaFrame(info, buffer, rowStrideBytes))
// 把 output 交回选定引擎的编码前处理接口。
// 切摄像头、重启视频源：stop/start；结束时 close。
```

## 输出与资源

- CPU 渲染产生独立 RGBA 缓冲；无效果时允许返回借用输入。不得在使用旁路输出前回收原帧。
- `GlBeautyRenderer` 要求宿主已持有同线程 ES3 EGL 上下文，支持 2D/OES；输出纹理只有效至下一次渲染或关闭，需要异步编码时由宿主复制或同步消费。
- 纹理处理不隐式读回 GPU。宿主用 `submitAnalysis` 提供与纹理逻辑画面一致的 RGBA 副本，尺寸、方向、镜像、时间线须一致；可先提交分析图再处理同时间戳纹理。
- 分析内部复制并缩小图像，单 worker 忙时丢帧、不积压；尺寸、方向、镜像变化会失效旧结果，同尺寸切摄像头仍需 stop/start。
- 超过 `maxResultAgeNs`、无脸或缺少真实锚点时隐藏挂件。设置对象可跨线程整体替换，生命周期和渲染仍须同线程。

当前是基础磨皮、美白、暖冷色调及程序化眼镜/胡子/礼帽原型，不包含精细瘦脸、大眼、分割遮挡、LUT 或贴纸素材系统。CPU 实现用于参考验证；持续直播速度须按真机测量。

## 人脸挂件

`BeautySettings` 的 `glasses`、`moustache`、`hat` 独立控制眼镜、胡子、礼帽；可叠加，`animateStickers` 控制轻微周期缩放。礼帽为蓝色程序化 2D 形状，使用双眼间距缩放、眼线角度旋转，并在镜像画面中修正朝向；它不是头部分割或精确头顶测量。

MediaPipe 后端提供三种效果所需锚点。只有人脸框的 RFB 默认不显示挂件；另配五点模型后可支持眼镜和粗定位礼帽，但胡子仍需真实上唇锚点。丢脸、结果超时或缺少对应锚点时隐藏挂件，基础美颜继续处理。

Demo 的静态图和相机页均提供三种开关，选择 MediaPipe 后端后对准人脸测试。当前不含可下载 PNG 素材包、3D 模型、手势触发或遮挡分割系统。
