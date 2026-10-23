# DevLib 逐库分析

分析日期：2026-10-23。依据当前源码、模块 build.gradle、Manifest、公开签名和已保存测试证据；本轮只分析及补文档，未修改库实现、未新增运行验收、未查询上游版本。

## 总体判断

现有 45 个模块为 39 个 Android AAR、5 个 JVM JAR、1 个 Play Asset Pack，覆盖基础框架、路由、文件、图像、文档、账号商业化、拍摄和直播。全部具备已验证的构建/发布入口，但不是 45 套都已完成设备集成的成品功能。

现有 JVM 基线为 785 项：784 通过、0 失败、1 原有跳过。13 个库没有既有 JVM 测试。数字表示已保存测试证据，不表示本轮重新执行，也不代表真实服务器、模型/Native、授权或 UI 已验收。

目前 App 直接依赖 core_base、core_utils 和演示 feature，间接使用 XPopup；其他功能库没有全部接成演示页面。App 配置了模型 asset pack，也不能据此判定 on-demand 模型已经在 adb 安装包里可用。

普通库统一发布 com.wkq.dev:<模块名中的下划线改为连字符>:0.1.0-SNAPSHOT；Android 库与 JVM 库区别见逐项类型。插件 ID com.wkq.router 与资源包 portrait_model_assets 是另外两种接入方式。保留源 API 包名是迁入兼容策略，不要求源码包全部改为 com.wkq.dev。

## 优先关注的接入边界

| 优先级 | 源码事实 | 影响与建议 |
| --- | --- | --- |
| 优先 | core_pdf 的公开请求引用 ImageSource，但 core_image_render 为 implementation | 单独消费 PDF 坐标的调用方可能缺少构造请求需要的类型；应改 API 暴露或调整公共请求，并增加独立消费样例。当前外部样例未覆盖此库。 |
| 优先 | lib_camera_capture.bindPreview 暴露 PreviewView/LifecycleOwner，相关依赖未完整按 api 声明 | 需要完整公开签名审计；同仓宿主已有依赖可能掩盖问题。 |
| 优先 | core_qr_transfer 构造和状态暴露 CoroutineScope/StateFlow，而协程为 implementation | 应显式暴露 API 依赖或封装对外接口；不应依赖宿主偶然引入协程。 |
| 优先 | lib_location 的 debug Manifest 含 AMAP_API_KEY，且高德实现为 debugImplementation | 源码 debug 宿主需配置 Key；release Maven AAR 不包含 debug 高德实现。应将高德适配拆成明确的可选库。 |
| 后续 | 现有发布消费样例只验证基础库/网络/路由/Drop/文档/权益等部分能力 | 不能推断所有 44 个坐标的所有 API 都已在外部宿主验证。建议按域补最小消费工程。 |
| 后续 | Native 引擎只声明 ARMv7/ARM64，模型为按需 Asset Pack | x86_64 模拟器跑 App 壳不能验证图片引擎；需要 ARM 设备与 Bundle/Play 配送验证。 |
| 后续 | Billing 默认 24 小时离线缓存；DevHub 权益默认 7 天宽限；lib_entitlement 是第三套快照模型 | 由宿主统一账号、状态和离线时限；不要分别判定后简单 OR 成一个 Pro。 |

以上 API 可见性结论来自静态签名/依赖检查，是待补的独立接入验证项，不把它们描述成已复现的设备崩溃。本轮未做自动修复。

## 逐库说明

下列测试数为发现的用例总数，包含跳过项；0 表示没有对应测试证据，不表示功能测试通过。

### 基础框架

#### 1. core_base

页面基础层：Activity/Fragment、ViewModel/事件、列表 Adapter、标题栏、权限入口、窗口 Insets 和通用对话框。

- 入口：`BaseActivity、BaseVMActivity、BaseFragment、BaseViewModel、DialogKit`。
- 模块：aar；src/main 代码文件 34；直接内部依赖：api lib_xpopup。测试：12 项。
- 边界：用于 XML/ViewBinding 页面；不是业务页面集合。CoreBase 已通过 api 引入本地 XPopup。
- 建议：保留为稳定底座，补齐多窗口、字体缩放、横竖屏及页面恢复的真机样例。
- 核对入口：[源码目录](../core/core_base/src/main)、[构建配置](../core/core_base/build.gradle)、[接入说明](../core/core_base/README.md)。

#### 2. core_utils

工具集合：文件/Uri、选择器、存储、Keystore、网络状态、图片加载、日志、深链跳转等。

- 入口：`CoreUtils、FileUtil、UriUtil、PermissionHelper、ImageLoaderUtil`。
- 模块：aar；src/main 代码文件 45；直接内部依赖：无本仓库 core 模块依赖（第三方依赖仍需按 build.gradle 解析）。测试：12 项。
- 边界：Application 统一初始化；已包含 Coil/MMKV，依赖面较宽，部分跳转处理器具有电商平台针对性。
- 建议：暂保留兼容 API；后续可把图片、存储、平台跳转拆成可选包，避免工具库持续膨胀。
- 核对入口：[源码目录](../core/core_utils/src/main)、[构建配置](../core/core_utils/build.gradle)、[接入说明](../core/core_utils/README.md)。

#### 3. core_network

客户端网络封装：Retrofit 服务、动态 BaseUrl/Header、统一响应/错误解析和上传下载状态。

- 入口：`Net、NetManager、ApiRetrofit、DownloadRetrofit、NetConfig`。
- 模块：aar；src/main 代码文件 20；直接内部依赖：无本仓库 core 模块依赖（第三方依赖仍需按 build.gradle 解析）。测试：6 项。
- 边界：适用于调用远端 HTTP API；不提供 LAN 服务端、NAS 持久任务或多协议上传调度。
- 建议：作为普通 API 网络入口；独立保留各领域库的协议实现，不为统一而强行替换。
- 核对入口：[源码目录](../core/core_network/src/main)、[构建配置](../core/core_network/build.gradle)、[接入说明](../core/core_network/README.md)。

#### 4. lib_xpopup

弹窗组件：确认/输入/列表/加载、底部、侧边、锚点、全屏及图片查看器。

- 入口：`XPopup.Builder、BasePopupView、CenterPopupView、BottomPopupView`。
- 模块：aar；src/main 代码文件 78；直接内部依赖：无本仓库 core 模块依赖（第三方依赖仍需按 build.gradle 解析）。测试：5 项。
- 边界：是完整 UI 库而非少量工具；与 CoreBase DialogKit 有封装层关系。
- 建议：业务优先用 DialogKit，特殊布局再直用 XPopup；补键盘、Insets、返回键和生命周期回归。
- 核对入口：[源码目录](../core/lib_xpopup/src/main)、[构建配置](../core/lib_xpopup/build.gradle)、[接入说明](../core/lib_xpopup/README.md)。

### 路由体系

#### 5. core_router_annotation

路由注解与注册契约，供业务编译及 processor 读取。

- 入口：`Route、Param、ProvideService、Interceptor、IRouteGroup、IRouteInit`。
- 模块：jar；src/main 代码文件 7；直接内部依赖：无本仓库 core 模块依赖（第三方依赖仍需按 build.gradle 解析）。测试：无既有 JVM 测试证据。
- 边界：Kotlin/JVM JAR；通常由运行时或插件传递引入。单独引入不会产生跳转能力。
- 建议：保持轻量，不放 Android 实现和业务路由路径。
- 核对入口：[源码目录](../core/core_router_annotation/src/main)、[构建配置](../core/core_router_annotation/build.gradle)、[接入说明](../core/core_router_annotation/README.md)。

#### 6. core_router_api

路由运行时：构建请求、加载路由表、拦截器、降级、参数注入、服务及结果回调。

- 入口：`Router、RouterConfig、Postcard、RouteTable`。
- 模块：aar；src/main 代码文件 9；直接内部依赖：api core_router_annotation。测试：无既有 JVM 测试证据。
- 边界：Android AAR，需要生成表及宿主初始化；只添加 annotation 无法替代运行时。
- 建议：补 Activity/Fragment 跳转、参数和返回结果的真机演示；当前运行时没有单独 JVM 测试。
- 核对入口：[源码目录](../core/core_router_api/src/main)、[构建配置](../core/core_router_api/build.gradle)、[接入说明](../core/core_router_api/README.md)。

#### 7. core_router_processor

KSP 代码生成：生成路由分组、初始化表、路径常量与注入代码，并验证配置。

- 入口：`RouteProcessor、RouteProcessorProvider`。
- 模块：jar；src/main 代码文件 2；直接内部依赖：implementation core_router_annotation。测试：3 项。
- 边界：构建期 JVM JAR，使用 ksp 配置而非 implementation 放入 App 运行时；与 Kotlin/KSP 工具链联动。
- 建议：保留生成正确性测试；多模块同名路径、混淆后注册和增量编译应增加场景。
- 核对入口：[源码目录](../core/core_router_processor/src/main)、[构建配置](../core/core_router_processor/build.gradle)、[接入说明](../core/core_router_processor/README.md)。

#### 8. core_router_gradle_plugin

接入自动化：应用 KSP，注入 API/processor 坐标和 moduleName。

- 入口：`插件 ID com.wkq.router；RouterExtension`。
- 模块：jar；src/main 代码文件 1；直接内部依赖：无本仓库 core 模块依赖（第三方依赖仍需按 build.gradle 解析）。测试：1 项。
- 边界：构建期插件，不是 Android 运行库；发布实现 JAR 与插件 marker。已修复依赖添加过晚导致 KSP 不生成的问题。
- 建议：独立消费样例已引用生成类并编译通过；后续覆盖宿主修改 group/version/开关及多模块场景。
- 核对入口：[源码目录](../core/core_router_gradle_plugin/src/main)、[构建配置](../core/core_router_gradle_plugin/build.gradle)、[接入说明](../core/core_router_gradle_plugin/README.md)。

### 文件与分享

#### 9. lib_drop

LAN 浏览器分享引擎：文件源、HTTP/HTTPS、Range、配对审批、下载队列、地址发现和连接建议。

- 入口：`ShareEngine、DropServer、ShareSource、UriShareSource、ConnectionAdvisor`。
- 模块：aar；src/main 代码文件 14；直接内部依赖：无本仓库 core 模块依赖（第三方依赖仍需按 build.gradle 解析）。测试：27 项。
- 边界：配对和授权主要保存在内存；不是云盘和长期后台下载器。宿主负责文件选择、网络权限、UI 和生命周期。
- 建议：与 lib_html 组合做第一套端到端示例；验证热点、断网、IPv6、浏览器证书和大文件。
- 核对入口：[源码目录](../core/lib_drop/src/main)、[构建配置](../core/lib_drop/build.gradle)、[接入说明](../core/lib_drop/README.md)。

#### 10. lib_html

接收网页资源：HTML/CSS/JS、多语言静态文件，以及固定路径到 assets 的映射。

- 入口：`OfflineWebAssets、WebAssetCatalog、WebAsset`。
- 模块：aar；src/main 代码文件 1；直接内部依赖：无本仓库 core 模块依赖（第三方依赖仍需按 build.gradle 解析）。测试：2 项。
- 边界：不启动服务器，不持有分享会话；与 lib_drop 互不依赖，由宿主把静态资源适配到服务器。
- 建议：保持前端/协议边界；补真实浏览器下载、错误页和多语言回归。
- 核对入口：[源码目录](../core/lib_html/src/main)、[构建配置](../core/lib_html/build.gradle)、[接入说明](../core/lib_html/README.md)。

#### 11. lib_upload

上传客户端：FTP、FTPS、SFTP、WebDAV、HTTP、SMB，支持进度、连接检测、配置/凭据存储。

- 入口：`UploadClient、UploadDispatcher、UploadServerProfile、UploadTask`。
- 模块：aar；src/main 代码文件 27；直接内部依赖：无本仓库 core 模块依赖（第三方依赖仍需按 build.gradle 解析）。测试：无既有 JVM 测试证据。
- 边界：有 ContentProvider 初始化配置存储；普通明文 FTP 和 FTPS 是不同模式。缺少本库既有 JVM 测试，不能用编译结果替代协议可靠性。
- 建议：优先补测试服务器与故障注入，覆盖取消、重试、原子上传、证书/主机指纹及日志脱敏。
- 核对入口：[源码目录](../core/lib_upload/src/main)、[构建配置](../core/lib_upload/build.gradle)、[接入说明](../core/lib_upload/README.md)。

#### 12. lib_transfer

文件管理及持久任务引擎：本地/SAF/SMB/WebDAV/SFTP、复制移动、冲突、恢复、同步、归档/回收及存储变化。

- 入口：`TransferClient、FileClient、V2Client、TransferRequest、StartupRecovery`。
- 模块：aar；src/main 代码文件 45；直接内部依赖：无本仓库 core 模块依赖（第三方依赖仍需按 build.gradle 解析）。测试：250 项。
- 边界：Room + WorkManager，属于重型引擎；advancedAllowed 由宿主注入。无启动页时通知可没有点击动作。
- 建议：复用价值高且测试最多；完善进程重启、撤销 SAF 权限、外置存储拔插、真实 NAS 和后台限制验证。
- 核对入口：[源码目录](../core/lib_transfer/src/main)、[构建配置](../core/lib_transfer/build.gradle)、[接入说明](../core/lib_transfer/README.md)。

#### 13. core_archive

ZIP 创建与带密码归档解压，包含压缩等级、加密选项、预算、取消及原子输出。

- 入口：`ZipArchiveCreator、ZipArchiveOperations、EncryptedArchiveExtractor`。
- 模块：aar；src/main 代码文件 3；直接内部依赖：api lib_operations。测试：13 项。
- 边界：依赖 Zip4j 与共享操作契约；不等同于支持所有 RAR/7z 格式的通用压缩引擎。
- 建议：保留独立库；补压缩炸弹、路径穿越、超大文件和密码错误的设备/文件语料验证。
- 核对入口：[源码目录](../core/core_archive/src/main)、[构建配置](../core/core_archive/build.gradle)、[接入说明](../core/core_archive/README.md)。

#### 14. core_share

Android 系统分享封装：单/多 content Uri、MIME、ClipData 与读授权。

- 入口：`ShareLauncher、ShareRequest`。
- 模块：aar；src/main 代码文件 1；直接内部依赖：无本仓库 core 模块依赖（第三方依赖仍需按 build.gradle 解析）。测试：无既有 JVM 测试证据。
- 边界：只是调用系统分享面板；不进行 LAN 配对或文件传输。宿主必须提供可授权的 content Uri。
- 建议：与 core_utils.ShareUtil 梳理重复入口，统一新代码的推荐入口；补接收端授权验证。
- 核对入口：[源码目录](../core/core_share/src/main)、[构建配置](../core/core_share/build.gradle)、[接入说明](../core/core_share/README.md)。

### 图片与元数据

#### 15. lib_operations

图片/文件处理的公共契约：预算、进度、取消、错误、统计、输出路径互斥与临时文件清理。

- 入口：`OperationRunner、OperationContext、OperationResult、AtomicFileOutput`。
- 模块：aar；src/main 代码文件 4；直接内部依赖：无本仓库 core 模块依赖（第三方依赖仍需按 build.gradle 解析）。测试：12 项。
- 边界：源码看起来偏通用，但使用 Android 日志/API，目前是 Android AAR，不能当纯 JVM 库使用。
- 建议：作为图片子系统共同底座；若未来需要桌面复用，再分离平台日志等 Android 依赖。
- 核对入口：[源码目录](../core/lib_operations/src/main)、[构建配置](../core/lib_operations/build.gradle)、[接入说明](../core/lib_operations/README.md)。

#### 16. core_image_render

Bitmap 渲染：批注/撤销历史、水印、设备样机、拍立得相框和签名提取。

- 入口：`AnnotationRenderer、WatermarkRenderer、DeviceMockupRenderer、ImageSource`。
- 模块：aar；src/main 代码文件 7；直接内部依赖：api lib_operations。测试：16 项。
- 边界：与 Native Vips 的职责不同，偏 UI 侧 Bitmap 合成；大图受解码内存和操作预算约束。
- 建议：保持轻量渲染层，补 EXIF 方向、透明通道、超大图和低内存设备验证。
- 核对入口：[源码目录](../core/core_image_render/src/main)、[构建配置](../core/core_image_render/build.gradle)、[接入说明](../core/core_image_render/README.md)。

#### 17. phototoolkit-engine

libvips/JNI 图像处理、压缩缩放与合成；另包含证件照制作、质量/构图检查、人像分析和抠图管线。

- 入口：`VipsNative、VipsOperationExecutor、IdPhotoProcessor、IdPhotoComplianceEvaluator`。
- 模块：aar；src/main 代码文件 16；直接内部依赖：api lib_operations。测试：25 项。
- 边界：并非单纯图片工具；依赖预编译 Native、LiteRT、ML Kit 和 Play Asset Delivery。当前声明 ARMv7/ARM64 ABI，不支持 x86_64 引擎运行。
- 建议：保留引擎边界，后续把通用图像、证件照策略、人像模型适配拆层；补 ARM 加载、内存、16KB 和大图基准。
- 核对入口：[源码目录](../core/phototoolkit-engine/src/main)、[构建配置](../core/phototoolkit-engine/build.gradle)、[接入说明](../core/phototoolkit-engine/README.md)。

#### 18. phototoolkit-raw

LibRaw/JNI 解码、白平衡/输出选项，并与图片引擎串接 RAW 处理流程。

- 入口：`RawNative、RawDevelopOptions、RawOperationExecutor`。
- 模块：aar；src/main 代码文件 3；直接内部依赖：api phototoolkit-engine、api lib_operations。测试：3 项。
- 边界：依赖 phototoolkit-engine 和共享 Native 运行库；不是单独放一个 raw AAR 就能摆脱 Vips 依赖。
- 建议：保持独立可选；补不同相机 RAW、颜色、方向、内存与解码失败的真实语料。
- 核对入口：[源码目录](../core/phototoolkit-raw/src/main)、[构建配置](../core/phototoolkit-raw/build.gradle)、[接入说明](../core/phototoolkit-raw/README.md)。

#### 19. lib_metadata

EXIF/IPTC/XMP 读写、sidecar、隐私清理、格式策略、校验及模板模型；含原生容器处理保护服务。

- 入口：`MetadataRecord、ExifWriter、IptcWriter、XmpWriter、MetadataVerifier`。
- 模块：aar；src/main 代码文件 32；直接内部依赖：无本仓库 core 模块依赖（第三方依赖仍需按 build.gradle 解析）。测试：92 项，其中 1 项跳过。
- 边界：已和原照片数据库脱钩，但仍包含模板/Room、Native 和前台服务；不是纯文本标签工具。92 项中有 1 项上游原有跳过。
- 建议：优先补设备上的原图完整性、不同容器/HEIF 和后台处理；新 DTO 与旧包名的兼容关系需继续文档化。
- 核对入口：[源码目录](../core/lib_metadata/src/main)、[构建配置](../core/lib_metadata/build.gradle)、[接入说明](../core/lib_metadata/README.md)。

#### 20. core_media_store

媒体输入、内部产物登记、DocumentTree 输出和发布到 MediaStore。

- 入口：`MediaFileStore、InternalArtifactStore、MediaStorePublisher、DocumentTreeOutput`。
- 模块：aar；src/main 代码文件 4；直接内部依赖：api lib_operations。测试：14 项。
- 边界：包含 Room 数据库；区分 App 私有中间文件、用户选择目录和公开相册，不承担图片编辑。
- 建议：作为图片输出统一落点，补系统照片权限、SAF 授权、取消清理和重复发布验证。
- 核对入口：[源码目录](../core/core_media_store/src/main)、[构建配置](../core/core_media_store/build.gradle)、[接入说明](../core/core_media_store/README.md)。

#### 21. id-photo-library

证件照规格/规则资料库：国家/地区、证件/渠道、尺寸、约束、证据来源、多语言和版本包安装校验。

- 入口：`IdPhotoLibraryRepository、CatalogPackageValidator、IdPhotoLibraryDatabase`。
- 模块：aar；src/main 代码文件 15；直接内部依赖：无本仓库 core 模块依赖（第三方依赖仍需按 build.gradle 解析）。测试：12 项。
- 边界：管理规则数据，不处理像素；数据真实性、更新与来源审核由资料维护流程承担，不能推断所有国家规格已覆盖。
- 建议：和 IdPhotoProcessor 通过明确映射衔接；补签名包安装、升级回滚和规则覆盖清单。
- 核对入口：[源码目录](../core/id-photo-library/src/main)、[构建配置](../core/id-photo-library/build.gradle)、[接入说明](../core/id-photo-library/README.md)。

#### 22. portrait-model-assets

按需下载的人像模型资源包，包含 modnet.tflite、模型清单和授权说明。

- 入口：`Play Asset Pack：portrait_model_assets`。
- 模块：asset-pack；src/main 代码文件 0；直接内部依赖：无本仓库 core 模块依赖（第三方依赖仍需按 build.gradle 解析）。测试：无既有 JVM 测试证据。
- 边界：不是 AAR/JAR；on-demand 配送依赖 App Bundle/Play 流程。普通 adb 安装 APK 不能证明按需模型下载可用。
- 建议：保留独立配送；增加下载状态、离线/失败回退和模型校验的设备演示。
- 核对入口：[源码目录](../core/portrait-model-assets/src/main)、[构建配置](../core/portrait-model-assets/build.gradle)、[接入说明](../core/portrait-model-assets/README.md)。

### 二维码与近距

#### 23. core_qrcode

二维码内容模型、绘制样式、Logo/背景处理、生成/识别和 Camera 扫码 Activity。

- 入口：`QrCodeKit、QRCodeGenerator、QRCodeRequest、QrLiveScanActivity`。
- 模块：aar；src/main 代码文件 7；直接内部依赖：api lib_operations。测试：13 项。
- 边界：底层 ZXingLite；不是文件传输协议。扫码 Activity 公开继承第三方类，第三方 API 可见性应专项检查。
- 建议：作为二维码能力统一入口；补摄像头授权、连续扫描和各样式识别率测试。
- 核对入口：[源码目录](../core/core_qrcode/src/main)、[构建配置](../core/core_qrcode/build.gradle)、[接入说明](../core/core_qrcode/README.md)。

#### 24. core_qr_transfer

扫码会话与 Nearby 文件传输：消息/清单分块、进度、接收落盘、连接证明与完整性校验。

- 入口：`QrTransferCoordinator、GoogleNearbyTransferEngine、QrSessionCodec`。
- 模块：aar；src/main 代码文件 7；直接内部依赖：api lib_operations。测试：10 项。
- 边界：与 lib_drop 不同：这里使用 Nearby 设备互传，不是浏览器 HTTP。宿主提供权限、二维码 UI 和 CoroutineScope。
- 建议：公开签名使用 CoroutineScope/StateFlow，但协程依赖为 implementation；补单坐标 API 消费及双机真机验证。
- 核对入口：[源码目录](../core/core_qr_transfer/src/main)、[构建配置](../core/core_qr_transfer/build.gradle)、[接入说明](../core/core_qr_transfer/README.md)。

### 文档导出

#### 25. lib_document

轻量 OpenXML 写入：DOCX 段落/表格/主题与 XLSX 表格输出。

- 入口：`SimpleDocxWriter、SimpleXlsxWriter、WordReportTheme`。
- 模块：jar；src/main 代码文件 4；直接内部依赖：无本仓库 core 模块依赖（第三方依赖仍需按 build.gradle 解析）。测试：6 项。
- 边界：纯 Kotlin/JVM JAR；不是 Office 编辑器/渲染器，不包含原 RoofQuote 的计价与模板策略。
- 建议：很适合直接复用；补 Word/Excel/LibreOffice 打开结果、中文字体和大表格样例。
- 核对入口：[源码目录](../core/lib_document/src/main)、[构建配置](../core/lib_document/build.gradle)、[接入说明](../core/lib_document/README.md)。

#### 26. core_pdf

把图片输出为 PDF：页面尺寸/方向、缩放、网格、页眉页脚、页码与 File/Uri/PFD 输出。

- 入口：`PdfImageExporter、PdfOperationRequest、PdfDocumentRequest`。
- 模块：aar；src/main 代码文件 1；直接内部依赖：api lib_operations、implementation core_image_render。测试：5 项。
- 边界：基于 Android PdfDocument；不是 PDF 解析/阅读库。公开请求使用 core_image_render.ImageSource，而对应依赖目前为 implementation。
- 建议：优先补 API 可见性/单坐标消费；与报告 PDF 共存，不要把图集工具变成报告业务引擎。
- 核对入口：[源码目录](../core/core_pdf/src/main)、[构建配置](../core/core_pdf/build.gradle)、[接入说明](../core/core_pdf/README.md)。

#### 27. lib_report_pdf

报告型 PDF 排版：公司/摘要、问题条目、照片、签名、状态/证据和模板样式。

- 入口：`NativeReportPdfRenderer、NativeReportData、NativeReportTemplate`。
- 模块：aar；src/main 代码文件 18；直接内部依赖：无本仓库 core 模块依赖（第三方依赖仍需按 build.gradle 解析）。测试：17 项。
- 边界：名称中的 Native 指原生 Android 绘制，不应据此认定为 C++ 引擎；数据仍带报告领域模型。
- 建议：适合巡检/工程报告；逐步抽象标签和布局插槽，补分页、长文本、中文与图片方向验收。
- 核对入口：[源码目录](../core/lib_report_pdf/src/main)、[构建配置](../core/lib_report_pdf/build.gradle)、[接入说明](../core/lib_report_pdf/README.md)。

### 账号与商业化

#### 28. lib_auth_credentials

Credential Manager 的 Google 登录协调、ID Token 载荷与 claims 模型。

- 入口：`GoogleSignInCoordinator、GoogleAccountPayload、GoogleIdTokenClaims`。
- 模块：aar；src/main 代码文件 3；直接内部依赖：无本仓库 core 模块依赖（第三方依赖仍需按 build.gradle 解析）。测试：无既有 JVM 测试证据。
- 边界：获取身份凭据，不负责业务账号和服务端验签；本地解码 claims 不代表可信登录。
- 建议：新接入优先评估该路径，补取消/重登、多账号和缺失服务场景；当前无本库 JVM 测试。
- 核对入口：[源码目录](../core/lib_auth_credentials/src/main)、[构建配置](../core/lib_auth_credentials/build.gradle)、[接入说明](../core/lib_auth_credentials/README.md)。

#### 29. lib_auth_google

已有 Google Sign-In 接入：配置、代理 Activity、登录/登出和结果/错误回调。

- 入口：`GoogleAuthManager、GoogleAuthConfig、GoogleLoginResult`。
- 模块：aar；src/main 代码文件 6；直接内部依赖：无本仓库 core 模块依赖（第三方依赖仍需按 build.gradle 解析）。测试：无既有 JVM 测试证据。
- 边界：与 Credential Manager 版本是并列认证路径，接口和包名不同；不能只因名称相似就同时初始化。
- 建议：保留兼容用途；由宿主选定一种 Google 登录实现并统一账号会话抽象。
- 核对入口：[源码目录](../core/lib_auth_google/src/main)、[构建配置](../core/lib_auth_google/build.gradle)、[接入说明](../core/lib_auth_google/README.md)。

#### 30. lib_auth_firebase

把 Google 凭据换成 FirebaseAuth 会话，支持当前用户、退出和删除账号。

- 入口：`DropGoogleAccount`。
- 模块：aar；src/main 代码文件 1；直接内部依赖：api lib_auth_credentials。测试：无既有 JVM 测试证据。
- 边界：依赖 lib_auth_credentials；宿主初始化 FirebaseApp。仍保留 Drop 前缀，属于历史 API 命名，并非通用后台账号系统。
- 建议：可增加中性命名门面及兼容别名；补过期凭据、重新认证和删除失败流程。
- 核对入口：[源码目录](../core/lib_auth_firebase/src/main)、[构建配置](../core/lib_auth_firebase/build.gradle)、[接入说明](../core/lib_auth_firebase/README.md)。

#### 31. lib_push_fcm

FCM Token、消息解析、通知显示与点击分发，包含代理入口和监听器。

- 入口：`GooglePushManager、GooglePushConfig、GoogleMessagingService`。
- 模块：aar；src/main 代码文件 17；直接内部依赖：无本仓库 core 模块依赖（第三方依赖仍需按 build.gradle 解析）。测试：12 项。
- 边界：和登录独立；宿主提供 Firebase 配置、通知渠道/权限及业务路由，不等于服务器消息发送平台。
- 建议：补前台/后台/杀进程、Token 轮换、通知拒绝与深链恢复的设备场景。
- 核对入口：[源码目录](../core/lib_push_fcm/src/main)、[构建配置](../core/lib_push_fcm/build.gradle)、[接入说明](../core/lib_push_fcm/README.md)。

#### 32. lib_billing_play

Play 商品查询、购买、恢复、订阅替换、状态与账号隔离缓存，宿主注入产品/计划/门禁。

- 入口：`GoogleBillingRuntime、GoogleBillingManager、GoogleBillingGateway`。
- 模块：aar；src/main 代码文件 11；直接内部依赖：无本仓库 core 模块依赖（第三方依赖仍需按 build.gradle 解析）。测试：27 项。
- 边界：采用 NAS 核心基线，七份支付副本未语义合并；不含付费 UI/业务商品规则，也不能替代服务端购买校验。
- 建议：保持支付事务层；补 Play 测试购买、待处理/退款/跨账号恢复，以及与服务端权益的状态映射。
- 核对入口：[源码目录](../core/lib_billing_play/src/main)、[构建配置](../core/lib_billing_play/build.gradle)、[接入说明](../core/lib_billing_play/README.md)。

#### 33. lib_entitlement

小型纯 JVM 权益判断：账号作用域、能力集、状态、验证/过期时间和时间回拨保护。

- 入口：`EntitlementSnapshot、EntitlementPolicy`。
- 模块：jar；src/main 代码文件 1；直接内部依赖：无本仓库 core 模块依赖（第三方依赖仍需按 build.gradle 解析）。测试：2 项。
- 边界：只判断输入快照是否可用；不向商店/后台核验购买，也不自动桥接 Billing 或 DevHub。
- 建议：适合做统一策略模型；增加宿主适配器及未知/离线/撤销语义，不把它当独立支付系统。
- 核对入口：[源码目录](../core/lib_entitlement/src/main)、[构建配置](../core/lib_entitlement/build.gradle)、[接入说明](../core/lib_entitlement/README.md)。

#### 34. lib_devhub

DevHub Android 客户端：活动/公告/升级入口、事件队列、反馈、服务端权益、缓存及 Play Integrity。

- 入口：`DevHub.initialize、DevHubConfig、CampaignsActivity`。
- 模块：aar；src/main 代码文件 5；直接内部依赖：无本仓库 core 模块依赖（第三方依赖仍需按 build.gradle 解析）。测试：145 项。
- 边界：不是 DevLib 本地工具中心；需要 DevHub 后端 apiUrl/appKey。默认 collectionEnabled=false，采集/展示需宿主控制。
- 建议：保留业务服务适配身份；145 项测试较多，但仍需后端契约验证；统一其权益宽限期与其他权益层策略。
- 核对入口：[源码目录](../core/lib_devhub/src/main)、[构建配置](../core/lib_devhub/build.gradle)、[接入说明](../core/lib_devhub/README.md)。

### 位置与拍摄

#### 35. lib_location

系统/Google 定位、回退定位、离线地理编码、GeoNames 数据导入和查询。

- 入口：`AppLocationProvider、FallbackAppLocationProvider、OfflineGeoCoder、GeoNamesImporter`。
- 模块：aar；src/main 代码文件 17；直接内部依赖：无本仓库 core 模块依赖（第三方依赖仍需按 build.gradle 解析）。测试：7 项。
- 边界：离线地理编码需要数据，不等于无数据即可返回城市。高德实现和其 Key 占位符只在 debug 变体。
- 建议：将高德做明确可选适配；当前源码宿主 debug 集成需处理 AMAP_API_KEY，release 发布产物不含该 debug 适配。
- 核对入口：[源码目录](../core/lib_location/src/main)、[构建配置](../core/lib_location/build.gradle)、[接入说明](../core/lib_location/README.md)。

#### 36. lib_camera

CameraX 预览/镜头/状态和帧输出，附带旋转/镜像信息，适合作为实时分析与美颜输入。

- 入口：`CustomCameraController、CameraConfig、CameraFrameListener`。
- 模块：aar；src/main 代码文件 2；直接内部依赖：无本仓库 core 模块依赖（第三方依赖仍需按 build.gradle 解析）。测试：5 项。
- 边界：不直接依赖美颜库；宿主要做 CameraFrame 到 VideoFrame 的转换并负责相机权限/生命周期。
- 建议：保持帧源专职，补前后摄切换、旋转、暂停恢复、背压及性能样例。
- 核对入口：[源码目录](../core/lib_camera/src/main)、[构建配置](../core/lib_camera/build.gradle)、[接入说明](../core/lib_camera/README.md)。

#### 37. lib_camera_capture

CameraX 静态拍照、文件/相册保存、位置接入、EXIF 以及照片加密签名辅助。

- 入口：`CameraController、MediaStorePhotoSaver、CameraLocationProvider、PhotoCryptographicSigner`。
- 模块：aar；src/main 代码文件 6；直接内部依赖：implementation core_utils、api lib_location。测试：无既有 JVM 测试证据。
- 边界：偏带证据信息的拍照流程，和 lib_camera 不能简单互换。公开 bindPreview 使用 PreviewView/LifecycleOwner，部分底层依赖为 implementation。
- 建议：补公开类型消费检查；把签名密钥来源、定位权限和证据验证交给宿主明确配置。
- 核对入口：[源码目录](../core/lib_camera_capture/src/main)、[构建配置](../core/lib_camera_capture/build.gradle)、[接入说明](../core/lib_camera_capture/README.md)。

### 美颜与直播

#### 38. lib_beauty

自有美颜处理核心：帧模型、会话、分析调度、能力/挂件、RGBA 和 GL 渲染。

- 入口：`BeautySession、BeautySettings、FaceAnalyzer、RgbaBeautyRenderer、GlBeautyRenderer`。
- 模块：aar；src/main 代码文件 10；直接内部依赖：无本仓库 core 模块依赖（第三方依赖仍需按 build.gradle 解析）。测试：19 项。
- 边界：不创建相机、不推流；异步分析、渲染线程与帧生命周期需要调用方遵守。
- 建议：保留为后端无关核心；补纹理、旋转、镜像、资源释放与低端机性能验证。
- 核对入口：[源码目录](../core/lib_beauty/src/main)、[构建配置](../core/lib_beauty/build.gradle)、[接入说明](../core/lib_beauty/README.md)。

#### 39. lib_beauty_rfb

基于 ONNX 的 RFB 人脸检测，以及可选五点精修，装配共享美颜会话。

- 入口：`RfbBeauty.createSession、RfbFaceAnalyzer、OnnxFivePointRefiner`。
- 模块：aar；src/main 代码文件 4；直接内部依赖：api lib_beauty。测试：4 项。
- 边界：已带 RFB 模型；未提供关键点精修时部分眼/唇挂件能力受限，不等于稠密人脸网格。
- 建议：与 MediaPipe 作为可选后端，按设备性能与能力需求选择，避免默认同时打包。
- 核对入口：[源码目录](../core/lib_beauty_rfb/src/main)、[构建配置](../core/lib_beauty_rfb/build.gradle)、[接入说明](../core/lib_beauty_rfb/README.md)。

#### 40. lib_beauty_mediapipe

MediaPipe FaceLandmarker 分析后端，提供稠密关键点和共享 FaceObservation。

- 入口：`MediaPipeFaceAnalyzer、MediaPipeFaceAnalyzer.factory`。
- 模块：aar；src/main 代码文件 1；直接内部依赖：api lib_beauty。测试：无既有 JVM 测试证据。
- 边界：本地打包 face_landmarker.task；当前 VIDEO CPU 模式、单人脸，仍有模型/原生运行库成本。
- 建议：用于更丰富挂件/几何分析；补模型加载、温升、延迟与关闭资源的设备测试。
- 核对入口：[源码目录](../core/lib_beauty_mediapipe/src/main)、[构建配置](../core/lib_beauty_mediapipe/build.gradle)、[接入说明](../core/lib_beauty_mediapipe/README.md)。

#### 41. lib_live_api

直播媒体抽象：会话、角色/模式、视频/屏幕配置、事件结果和纹理美颜契约。

- 入口：`LiveMediaEngine、MediaSession、MediaEvent、TextureBeautyProcessor`。
- 模块：aar；src/main 代码文件 3；直接内部依赖：无本仓库 core 模块依赖（第三方依赖仍需按 build.gradle 解析）。测试：无既有 JVM 测试证据。
- 边界：属于 Android AAR；只定义接口，不包含信令服务器、RTC 房间后端或付费策略。
- 建议：作为 RTC 适配共同边界；与 lib_beauty 的帧模型独立，跨体系需要明确转换层。
- 核对入口：[源码目录](../core/lib_live_api/src/main)、[构建配置](../core/lib_live_api/build.gradle)、[接入说明](../core/lib_live_api/README.md)。

#### 42. lib_agora

Agora RTC 实现：会话/角色、音视频配置、屏幕与音量事件、帧处理/美颜桥接。

- 入口：`AgoraLiveEngine、AgoraLiveSession、AgoraMediaAdapter、AgoraVideoFrameProcessor`。
- 模块：aar；src/main 代码文件 5；直接内部依赖：api lib_live_api。测试：无既有 JVM 测试证据。
- 边界：依赖完整 Agora SDK 4.6.4，宿主提供 App ID/Token/频道、权限和生命周期；包体与商用服务成本需要单独评估。
- 建议：补双机通话、Token 续期、切后台、网络切换和纹理链路；当前无本库 JVM 测试。
- 核对入口：[源码目录](../core/lib_agora/src/main)、[构建配置](../core/lib_agora/build.gradle)、[接入说明](../core/lib_agora/README.md)。

#### 43. lib_live_beauty

把腾讯美颜纹理处理接到直播 API，含背景资源保存和 EGL/纹理槽生命周期。

- 入口：`TencentTextureProcessor、BeautyBackgroundStore`。
- 模块：aar；src/main 代码文件 2；直接内部依赖：api lib_live_api、api lib_tencent_beauty_kit。测试：无既有 JVM 测试证据。
- 边界：依赖 lib_live_api + 腾讯 Kit；不是自有 lib_beauty 的另一个 FaceAnalyzer。
- 建议：保持桥接职责，补 GL 上下文、纹理归还、分辨率变化和销毁重建的真机验证。
- 核对入口：[源码目录](../core/lib_live_beauty/src/main)、[构建配置](../core/lib_live_beauty/build.gradle)、[接入说明](../core/lib_live_beauty/README.md)。

#### 44. lib_tencent_beauty_kit

腾讯商业美颜 Kit 封装及参数、面板、下载、资源管理和自定义控件。

- 入口：`TEBeautyKit、TEParamManager、TEPanelView、TEDownloadManager`。
- 模块：aar；src/main 代码文件 48；直接内部依赖：无本仓库 core 模块依赖（第三方依赖仍需按 build.gradle 解析）。测试：无既有 JVM 测试证据。
- 边界：已包含较完整 UI 和 SDK 资源，不只是薄包装；需要腾讯授权/资源及网络。
- 建议：保持可选，避免作为所有 App 的基础依赖；把授权/下载错误及面板品牌配置文档化。
- 核对入口：[源码目录](../core/lib_tencent_beauty_kit/src/main)、[构建配置](../core/lib_tencent_beauty_kit/build.gradle)、[接入说明](../core/lib_tencent_beauty_kit/README.md)。

### 开发诊断

#### 45. lib_crash

可控触发 Java/Kotlin 和 Native 故障，用来验证崩溃收集、日志与恢复链路。

- 入口：`CrashUtils、CrashScenario、CrashType、NativeCrashScenario`。
- 模块：aar；src/main 代码文件 46；直接内部依赖：无本仓库 core 模块依赖（第三方依赖仍需按 build.gradle 解析）。测试：8 项。
- 边界：是主动制造崩溃的测试库，不是崩溃采集/上报 SDK。运行这些场景会故意终止进程。
- 建议：只在 debug/内部诊断入口启用；不要默认向生产用户暴露，补现有采集平台的端到端验证。
- 核对入口：[源码目录](../core/lib_crash/src/main)、[构建配置](../core/lib_crash/build.gradle)、[接入说明](../core/lib_crash/README.md)。

## 重叠能力如何取舍

| 看似重叠 | 实际区别 | 维护建议 |
| --- | --- | --- |
| core_utils / lib_operations | 通用 App 工具 vs 图片/文件操作预算和输出契约 | 不合并，避免图像引擎依赖整个工具库。 |
| core_network / lib_upload / lib_transfer / lib_drop / core_qr_transfer | HTTP 客户端 / 向服务器上传 / 持久文件任务与 NAS / 浏览器 LAN 服务端 / Nearby 设备互传 | 共享进度与错误适配即可，不强行合成一个万能网络库。 |
| core_share / core_utils.ShareUtil | 都有系统分享辅助，但前者有明确 ShareRequest 与 content Uri 约束 | 选一个新代码入口，旧 API 做兼容转发。 |
| core_pdf / lib_report_pdf / lib_document | 图片 PDF / 报告 PDF / DOCX-XLSX | 按输出内容选，不按扩展名强行合并。 |
| lib_auth_google / lib_auth_credentials / lib_auth_firebase | 旧 Google 登录 / Credential Manager 凭据 / Firebase 会话适配 | 宿主选定凭据入口，Firebase 层可在其上叠加，不重复管理账号。 |
| lib_camera / lib_camera_capture | 实时帧源 / 静态拍摄和证据辅助 | 保留场景边界；以后抽取小型 CameraX 共享实现时避免破坏两套 API。 |
| lib_beauty 系列 / lib_live_beauty + 腾讯 Kit | 自有分析和渲染 / 腾讯商业纹理后端 | 并列可选，靠适配器连接直播契约；不要把腾讯 SDK 强塞进自有核心。 |
| id-photo-library / phototoolkit-engine | 规格资料与证据 / 图像处理与证件照执行 | 明确数据到执行模型的映射和版本，不重复维护两份规格真相。 |
| lib_billing_play / lib_entitlement / lib_devhub | 商店支付事务 / 本地策略判断 / 服务端业务权益 | 一处统一会话和最终授权，分别提供数据/适配，不能互相冒充可信核验。 |

## 后续实施顺序

1. 先修清公开 API 依赖、定位 debug 占位符和宿主初始化说明，按域扩充外部消费验证。独立库可构建只是第一步。
2. 给分享、上传/传输、文档、图片各做一条完整演示链路。当前手机页面只验证 App 壳，不能用来验收全部库。
3. 补无测试模块的最小自动化与关键设备场景，尤其上传、账号、Camera Capture、RTC 和商业美颜。
4. 统一权益适配；再考虑工具库拆分、图像大引擎拆层及重复入口收敛。保留兼容 API，避免先大规模改包名。

相关事实：[模块总览](../README.md)、[来源快照](../docs/import-manifest.json)、[既有测试报告](testing/TEST_REPORT.md)、[机器可读分析](../docs/library-analysis.json)。
