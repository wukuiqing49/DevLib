# Agora 直播能力模块

本模块根据现有 Zego 调用点封装媒体能力，供后续页面迁移使用。当前页面仍走 Zego，本次不改房间业务、WebSocket、礼物、支付、座位数据和美颜配置。

## 现有实现与迁移边界

已梳理的主要实现：

- `AppQdLiveEngineUtils.kt`：视频/语音/游戏引擎，推拉流、CDN、音量、相机、续期和屏幕采集。
- `LiveBroadcastPrepareActivity.java`：预览、美颜、清晰度、开播、推流质量。
- `LiveBroadcastRoomActivity.java`：观看、连麦、PK、多路播放、房间切换、重试、首帧、尺寸和声浪。
- `LiveRoomHelper.kt`：语音房上下麦、禁麦、多人音频和全房停止播放。
- `DmGamePkView.kt`：游戏/左右主播多画面及独立音量。
- `PublishGameLiveActivity.kt`：游戏画面屏幕采集与 CDN 推流。
- `LiveVideoTencentEffectProcessor.kt`：腾讯美颜处理 Zego GL_TEXTURE_2D 帧。

页面使用现有 XML 和绑定方式，数据仍来自现有 Retrofit、ViewModel 和 WebSocket。媒体模块不需要数据库迁移，也不改变主题、多语言和布局适配。

## 能力对照

| 现有能力 | 模块 API | 接入说明 |
| --- | --- | --- |
| 创建/检查/销毁引擎 | `initialize` / `isInitialized` / `destroy` | 同一进程只能有一个模块引擎实例；不同 App ID 必须先销毁再创建 |
| 视频、语音、游戏模式 | `AgoraLiveSession.mode` | `VIDEO` / `VOICE` / `SCREEN`；语音模式关闭视频订阅 |
| 登录/退出/切换房间 | `join` / `leave` | `leave` 异步完成；收到主频道和额外频道的 `Left` 后再进新房 |
| 上麦/下麦/启动与停止发布 | `startPublishing` / `stopPublishing` | 语音房用 `startPublishing(true, false)`；不处理座位权限和座位数据 |
| 本地预览/停止/解绑 | `attachLocalVideo` / `stopPreview` / `detachLocalVideo` | 支持 FILL/FIT；View 销毁时解绑，切换到悬浮窗可重新绑定 |
| RTC 拉流/停流/多人音频 | `startPlaying` / `stopPlaying` / `attachRemoteVideo` | 同一频道按 uid 订阅；纯音频不传 View。远端 View 可解绑而不停止音频 |
| PK 多频道发布/订阅 | `joinExtraChannel` / `updateExtraChannel` / `leaveExtraChannel` / `attachExtraChannelVideo` | 活跃主播可用相同本人 UID 向其他频道发布；每个频道需独立 Token、相同 App ID。宿主确认参与者和发布权限，停发同时处理所有额外连接 |
| PK 独立音量/禁音/视频订阅 | `setExtraChannelPlaybackVolume` / `setExtraChannelAudioMuted` / `setExtraChannelVideoMuted` | 不代替业务 PK 信令或云端混流布局 |
| 麦克风与发布静音 | `muteMicrophone` / `setLocalAudioMuted` / `setLocalVideoMuted` | 麦克风录音信号静音与发布流静音分别控制 |
| 单路/全局音量 | `setRemotePlaybackVolume` / `setPlaybackVolume` / `setAllPlaybackVolume` / `setRecordingVolume` | RTC 音量 0..400；全局 RTC+CDN 音量 0..100，作用于当前和新建 URL 播放器 |
| URL 播放与独立音量 | `startCdnPlayback` / `stopCdnPlayback` / `setCdnPlaybackVolume` / `attachCdnVideo` | 多播放器按业务 playerId 管理；SDK 播放器音量 0..100；鉴权 URL 由原业务层提供 |
| RTC 转推 CDN | `addPublishCdnUrl` / `removePublishCdnUrl` | 对应 SDK RTMP 转推，不等价于绕过 RTC 的 CDN 直推；需开通服务并确认已有 CDN URL 接受声网推流 |
| 相机/前后摄像头/手电筒 | `setCameraEnabled` / `useFrontCamera` / `switchCamera` / `isTorchSupported` / `setTorchEnabled` | 前后摄像头使用绝对选择；手电筒先检查支持情况 |
| 本地与发布镜像 | `setPreviewMirror` / `LiveVideoConfig.mirrorPublishedVideo` | 分别配置；仅设置预览镜像不会改变推流画面 |
| 视频分辨率/帧率/码率 | `setVideoConfig` / `getVideoConfig` / `LiveVideoConfig.portraitPreset` | 项目定制档位保留 360×480、480×640、720×960、960×1280，15 fps，600/800/1500/1500 kbps |
| 音频编码 | `setAudioProfile` | 使用声网标准 profile；旧 48/64/96/128 kbps 不能声称逐项等价，最终码率以统计为准 |
| 声浪 | `enableAudioVolumeIndication` / `stopAudioVolumeIndication` | 默认 800 ms；SDK 本地 uid=0，业务层映射为登录用户；声浪值 0..255，需调整旧 UI 阈值 |
| 人脸数量 | `enableFaceDetection` / `onFaceCountChanged` | 不代替业务人脸审核、活体检查 |
| 屏幕共享 | `setScreenProjection` / `startScreenShare` / `stopScreenShare` | 可配置宽高、fps 和设备音频；开启后停止发布摄像头轨，停止后恢复会话配置 |
| 腾讯美颜扩展 | `setVideoFrameProcessor` / `AgoraVideoFrameProcessor` | 注册可读写 Texture2D 帧观察者；业务层处理纹理并替换 frame.buffer；尚未对接腾讯实例 |
| Token 续期 | `renewToken` / `renewExtraChannelToken` / `TokenRequired` / `TokenRenewed` | 模块不请求网络、不持久化 Token、不包含 App Certificate |
| 质量与网络时间 | `VideoQuality` / `AudioQuality` / `NetworkQuality` / `networkTimeMs` | RTC 码率 kbps，丢包百分比，时间 ms；SDK 不提供旧 Zego 所有质量字段的等价值 |
| SDK 版本/日志上传 | `sdkVersion` / `uploadLog` / `onLogUploadResult` | 日志上传仅调用方显式触发；不输出 Token 或 URL 查询参数 |

## 会话协议必须由后端提供

额外频道入房期间暂存最新发布配置和 Token，收到成功回调后应用。屏幕采集启停同步额外发布频道的轨道；观众频道保持只订阅。异步角色切换失败反馈给房间会话，退出/销毁时清除频道配置，Token 不落盘。

当前接入以 `agora_low` 为准，原 `/api/misc/getZegoToken` 需明确携带 `rtc_provider=agora`、`type=video/voice` 和 `roomnumber` 才进入声网分支。业务层负责获取配置及 Token、调用统一 REST 代理；SDK 模块不处理这些 HTTP 请求。业务层需提供：

- `appId`：视频/屏幕共享读取 `agora_appid`，语音读取 `agora_voice_appid`，与各自 Token 的 `appID` 一致。配置由宿主接口获取，不使用本地常量兜底。语音麦上 RTC 并独立转推，观众由宿主按每个用户的 CDN 地址创建播放器。
- `channelId`：同一直播间的所有 RTC 参与者必须使用同一个频道。
- `uid`：本模块以 Int 保存 UInt32 的原始位模式，非零负 Int 也是合法 SDK UID；业务值范围为 1..4294967295。通过共享 MediaUid 严格解析和转回无符号数字，不对字符串用户 ID 做 hashCode。
- `token`：与 channelId、uid、发布权限一致，并提供发布权限续期；Token 在服务端签发。
- PK 远端的 channelId、远端 uid、本地入频道 uid 和该频道 Token。
- CDN 推拉流 URL：继续由后端签名，确认有效期、协议和跨供应商兼容性。

Agora 的观众角色不提供 Zego 房间内所有观众的进出列表，在线人数/成员列表继续使用现有业务协议。媒体 `RemoteUser` 主要用于发布者变化，不可直接计算房间人数。

跨供应商迁移期间，Zego 发布者与 Agora RTC 订阅者不能直接互通。房间需要按供应商区分会话并同步升级主播/连麦端，普通观众可以在后端 URL 兼容的前提下继续走 CDN 播放。旧 `zego_config_domain` 属于供应商配置，不能直接传给 Agora。

## Kotlin 接入顺序

```kotlin
// appId 和 session 来自业务层，不在客户端生成凭证。
val engine = AgoraLiveEngine(applicationContext, object : AgoraLiveListener {
    override fun onEvent(channelId: String, event: AgoraLiveEvent) {
        when (event) {
            AgoraLiveEvent.TokenRequired -> requestAgoraToken(channelId)
            AgoraLiveEvent.Left -> handleChannelLeft(channelId)
            else -> handleMediaEvent(channelId, event)
        }
    }
})
engine.initialize(appId)
engine.setVideoConfig(LiveVideoConfig.portraitPreset(540))
engine.attachLocalVideo(textureView)
// 美颜处理器在预览前注册；没有处理器时不要注册占位实现。
engine.join(session)
// join 的 role/publishAudio/publishVideo 决定是否立即发布。
// 会话以不发布状态入房时，开播/上麦再调用 startPublishing。
```

Java 页面使用 `AgoraLiveListenerAdapter`，只覆写需要的回调；`AgoraLiveSession` 构造器和常用默认参数方法提供 Java 重载。

优先使用 `onEvent(channelId, event)` 对接新页面。初版单独回调保留主频道兼容，同一事件不要同时从两套回调处理。Event 中 SDK state/reason/error 整数保持声网含义，不映射成 Zego 枚举；业务成功需等待 `PublishState` / `SubscribeState` / 首帧，返回 0 仅代表 SDK 接受请求。

所有控制方法在主线程调用，事件回调也派发主线程。`setListener(null)` 会取消旧监听接收，但不停止媒体；适用于页面切换或悬浮窗交接。退出页面需根据业务是否保留媒体决定 `leave` 或 `destroy`。销毁后重新使用该对象前必须重新设置监听。

上下麦需等待角色/发布状态回调后再执行下一次切换；失败通过 `RoleChangeFailed` 回报并恢复报告的角色状态。清晰度切换同时请求相机采集格式与编码格式，设备实际采集尺寸可能不同，以首帧和质量回调为准。

## 屏幕采集和美颜接入条件

屏幕采集权限与前台 Service 属于宿主工作流。Android 14+ 必须为每次采集取得有效授权，建立正确的 mediaProjection 前台服务后，将有效 MediaProjection 传入模块，再开启共享；后台麦克风采集另需 microphone 前台服务及对应权限。不要复用失效授权。当前宿主已有前台服务声明，但其中 Zego 专用采集服务不能直接用于 Agora，需要页面迁移阶段改接。

设备音频采集受到系统版本、被采集应用是否允许和 SDK 限制，不能保证所有游戏都可采音。屏幕共享是实时编码传输，不是录制/文件转码，不生成临时视频文件；编码会影响画质和码率，由 SDK 管理时间戳与音视频同步。

帧处理扩展在 SDK 视频线程同步执行，frame 是借用对象，不能直接保存到下一帧或阻塞等待主线程。处理器需正确管理 EGL Context、TextureBuffer、变换矩阵、rotation、纳秒时间戳和 retain/release。当前 `TencentBeautyKit.process(textureId, width, height)` 的输出纹理必须包装为 Agora buffer；不能仅替换 textureId，也不能调用旧的 Zego 回传方法。解绑处理器后再释放腾讯实例，销毁操作不得在帧回调内调用。

Gradle 使用 `api` 暴露 SDK，是为了帧处理扩展签名中的 `VideoFrame` 及业务对 state/reason 常量的解释；普通页面通过封装调用，不持有 RtcEngine。

## CDN 路径取舍

声网 4.6 起弃用 CDN 直推 API。本模块提供 RTC 发布后单流 RTMP 转推；没有封装已弃用的 `startDirectCdnStreaming`。对于当前游戏直播、语音房和主播的直推分支，接入时应改为 RTC + Media Push，后端开通能力并评估时延与计费。多主播混流、跨项目互通和合成画面仍需服务端方案，跨频道订阅不代表已实现跨频道发布/云端混流。

官方参考：

- [SDK 4.6 的直推弃用说明](https://agoraio.zendesk.com/hc/en-us/articles/40748554561428-Elevating-Real-Time-Engagement-Agora-Native-SDK-4-6-0-Released)
- [原始视频帧处理](https://docs.agora.io/en/realtime-media/rtc/build/capture-and-render-video/raw-video-processing)
- [Android 屏幕共享示例](https://github.com/AgoraIO/API-Examples/blob/main/Android/APIExample/app/src/main/java/io/agora/api/example/examples/advanced/ScreenSharing.java)

## 页面迁移顺序和验证范围

1. 后端确认会话协议及 CDN 转推；准备同 App ID 的主播/观众账号。
2. 开播准备页接入预览、清晰度、相机和腾讯帧处理，再验证普通开播/观看及 CDN。
3. 语音房接入多人订阅、上下麦、独立音量、声浪和续期。
4. 连麦/PK 验证主频道及额外频道发布、各路画面/音量、退出和失败停发。新宿主使用多频道发布让每个 PK 房间具备本频道混流源，仍需后台权限与真机云端输出联调。
5. 游戏直播接入 MediaProjection/Service 生命周期，再验证设备音频和锁屏后台恢复。
6. 检查悬浮窗交接、房间连续切换、权限拒绝、断网重连、Token 失效、进程重建和资源释放。

模块编译通过仅说明 SDK 类型和调用签名匹配。尚未完成账号/设备联调、R8 发布包、16 KB native 页面对齐或包体积验证；Zego 与 Agora 暂时共存会增加依赖和包体积，最终页面切换后才能删除旧 SDK。宿主完整构建结果以本次交付记录为准。
