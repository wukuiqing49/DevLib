# DevLib 逐库分析

当前范围：20 个模块，8 个基础模块、11 个功能库、1 个内部支撑库。已按用户确认范围移出 15 个功能模块；基础库保持原有实现。当前分类见 docs/library-catalog.json，移出来源见 docs/import-manifest.json 的 removedModules。

lib_operations 为保留功能提供预算、取消、进度与输出契约，不单独展示业务页面。编译与运行验收分开，结果见 [最新报告](testing/TEST_REPORT.md)。

## 保留模块

### core_base

来源：AndroidCoreBase；产物：aar。

用途：页面基础层：Activity/Fragment、ViewModel/事件、列表 Adapter、标题栏、权限入口、窗口 Insets 和通用对话框。

公开入口：BaseActivity、BaseVMActivity、BaseFragment、BaseViewModel、DialogKit

接入边界：用于 XML/ViewBinding 页面；不是业务页面集合。CoreBase 已通过 api 引入本地 XPopup。

建议：保留为稳定底座，补齐多窗口、字体缩放、横竖屏及页面恢复的真机样例。

模块依赖：api :core:lib_xpopup。

### core_network

来源：AndroidCoreNetwork；产物：aar。

用途：客户端网络封装：Retrofit 服务、动态 BaseUrl/Header、统一响应/错误解析和上传下载状态。

公开入口：Net、NetManager、ApiRetrofit、DownloadRetrofit、NetConfig

接入边界：适用于调用远端 HTTP API；不提供 LAN 服务端、NAS 持久任务或多协议上传调度。

建议：作为普通 API 网络入口；独立保留各领域库的协议实现，不为统一而强行替换。


### core_router_annotation

来源：AndroidCoreRouters；产物：jvm。

用途：路由注解与注册契约，供业务编译及 processor 读取。

公开入口：Route、Param、ProvideService、Interceptor、IRouteGroup、IRouteInit

接入边界：Kotlin/JVM JAR；通常由运行时或插件传递引入。单独引入不会产生跳转能力。

建议：保持轻量，不放 Android 实现和业务路由路径。


### core_router_api

来源：AndroidCoreRouters；产物：aar。

用途：路由运行时：构建请求、加载路由表、拦截器、降级、参数注入、服务及结果回调。

公开入口：Router、RouterConfig、Postcard、RouteTable

接入边界：Android AAR，需要生成表及宿主初始化；只添加 annotation 无法替代运行时。

建议：补 Activity/Fragment 跳转、参数和返回结果的真机演示；当前运行时没有单独 JVM 测试。

模块依赖：api :core:core_router_annotation。

### core_router_gradle_plugin

来源：AndroidCoreRouters；产物：jvm。

用途：接入自动化：应用 KSP，注入 API/processor 坐标和 moduleName。

公开入口：插件 ID com.wkq.router；RouterExtension

接入边界：构建期插件，不是 Android 运行库；发布实现 JAR 与插件 marker。已修复依赖添加过晚导致 KSP 不生成的问题。

建议：独立消费样例已引用生成类并编译通过；后续覆盖宿主修改 group/version/开关及多模块场景。


### core_router_processor

来源：AndroidCoreRouters；产物：jvm。

用途：KSP 代码生成：生成路由分组、初始化表、路径常量与注入代码，并验证配置。

公开入口：RouteProcessor、RouteProcessorProvider

接入边界：构建期 JVM JAR，使用 ksp 配置而非 implementation 放入 App 运行时；与 Kotlin/KSP 工具链联动。

建议：保留生成正确性测试；多模块同名路径、混淆后注册和增量编译应增加场景。

模块依赖：implementation :core:core_router_annotation。

### core_utils

来源：AndroidCoreUtils；产物：aar。

用途：工具集合：文件/Uri、选择器、存储、Keystore、网络状态、图片加载、日志、深链跳转等。

公开入口：CoreUtils、FileUtil、UriUtil、PermissionHelper、ImageLoaderUtil

接入边界：Application 统一初始化；已包含 Coil/MMKV，依赖面较宽，部分跳转处理器具有电商平台针对性。

建议：暂保留兼容 API；后续可把图片、存储、平台跳转拆成可选包，避免工具库持续膨胀。


### lib_xpopup

来源：XPopup；产物：aar。

用途：弹窗组件：确认/输入/列表/加载、底部、侧边、锚点、全屏及图片查看器。

公开入口：XPopup.Builder、BasePopupView、CenterPopupView、BottomPopupView

接入边界：是完整 UI 库而非少量工具；与 CoreBase DialogKit 有封装层关系。

建议：业务优先用 DialogKit，特殊布局再直用 XPopup；补键盘、Insets、返回键和生命周期回归。


## 功能库

### core_archive

来源：PhotoKit；产物：aar。

用途：ZIP 创建与带密码归档解压，包含压缩等级、加密选项、预算、取消及原子输出。

公开入口：ZipArchiveCreator、ZipArchiveOperations、EncryptedArchiveExtractor

接入边界：依赖 Zip4j 与共享操作契约；不等同于支持所有 RAR/7z 格式的通用压缩引擎。

建议：保留独立库；补压缩炸弹、路径穿越、超大文件和密码错误的设备/文件语料验证。

模块依赖：api :core:lib_operations。

### core_image_render

来源：PhotoKit；产物：aar。

用途：Bitmap 渲染：批注/撤销历史、水印、设备样机、拍立得相框和签名提取。

公开入口：AnnotationRenderer、WatermarkRenderer、DeviceMockupRenderer、ImageSource

接入边界：与 Native Vips 的职责不同，偏 UI 侧 Bitmap 合成；大图受解码内存和操作预算约束。

建议：保持轻量渲染层，补 EXIF 方向、透明通道、超大图和低内存设备验证。

模块依赖：api :core:lib_operations。

### core_document

来源：PhotoKit / RoofQuote；产物：Android AAR。原 core_pdf 与 lib_document 合并，公开包名保留。

用途：同一份记录的标题、时间、字段、备注、明细和图片生成 PDF、Excel、Word。入口为 ExportRecord / RecordExporter。

PDF 使用真实文字和表格，长内容跨页；Excel 分基本信息和明细工作表，数字显式声明；Word 保持可编辑段落和表格。

原图片 PDF 与轻量 OpenXML API 继续可用。整批临时生成后原子发布，线程中断取消。纯 JVM JAR 接入方式已改为 Android AAR。

边界与验证见 [记录导出](record-export.md)，不将构建或 XML 结构检查等同真实阅读器/视觉验收。

### core_qrcode

来源：PhotoKit；产物：aar。

用途：二维码内容模型、绘制样式、Logo/背景处理、生成/识别和 Camera 扫码 Activity。

公开入口：QrCodeKit、QRCodeGenerator、QRCodeRequest、QrLiveScanActivity

接入边界：底层 ZXingLite；不是文件传输协议。扫码 Activity 公开继承第三方类，第三方 API 可见性应专项检查。

建议：作为二维码能力统一入口；补摄像头授权、连续扫描和各样式识别率测试。

模块依赖：api :core:lib_operations。

### lib_drop

来源：DropLAN；产物：aar。

用途：LAN 浏览器分享引擎：文件源、HTTP/HTTPS、Range、配对审批、下载队列、地址发现和连接建议。

公开入口：ShareEngine、DropServer、ShareSource、UriShareSource、ConnectionAdvisor

接入边界：配对和授权主要保存在内存；不是云盘和长期后台下载器。宿主负责文件选择、网络权限、UI 和生命周期。

建议：由宿主提供接收页面，工作台使用 demo_receiver；验证热点、断网、IPv6、浏览器证书和大文件。


### lib_metadata

来源：PressIPTC；产物：aar。

用途：EXIF/IPTC/XMP 读写、sidecar、隐私清理、格式策略、校验及模板模型；含原生容器处理保护服务。

公开入口：MetadataRecord、ExifWriter、IptcWriter、XmpWriter、MetadataVerifier

接入边界：已和原照片数据库脱钩，但仍包含模板/Room、Native 和前台服务；不是纯文本标签工具。92 项中有 1 项上游原有跳过。

建议：优先补设备上的原图完整性、不同容器/HEIF 和后台处理；新 DTO 与旧包名的兼容关系需继续文档化。


### lib_operations

来源：PhotoKit；产物：aar。

用途：图片/文件处理的公共契约：预算、进度、取消、错误、统计、输出路径互斥与临时文件清理。

公开入口：OperationRunner、OperationContext、OperationResult、AtomicFileOutput

接入边界：源码看起来偏通用，但使用 Android 日志/API，目前是 Android AAR，不能当纯 JVM 库使用。

建议：作为图片子系统共同底座；若未来需要桌面复用，再分离平台日志等 Android 依赖。


### core_nas / core_usb

来源：NASFileManager；两个独立 AAR，保留公开包名。core_nas 提供 SMB/WebDAV/SFTP；core_usb 提供 SAF 授权目录访问，不提供 USB 驱动。宿主负责权限、凭据、IO 与确认；不迁入后台队列。

独立页面支持浏览、下载（8 MiB 上限）、独占上传、4 KiB 隔离读写验证。真实设备未验收。

### lib_upload

来源：PressIPTC；产物：aar。

用途：上传客户端：FTP、FTPS、HTTP（SMB、SFTP、WebDAV 统一使用 core_nas），支持进度、连接检测、配置/凭据存储。

公开入口：UploadClient、UploadDispatcher、UploadServerProfile、UploadTask

接入边界：有 ContentProvider 初始化配置存储；普通明文 FTP 和 FTPS 是不同模式。已有协议策略及回环 HTTP 测试，不能替代真实 FTP/FTPS/HTTP 服务器可靠性验收。

建议：优先补测试服务器与故障注入，覆盖取消、重试、原子上传、证书/主机指纹及日志脱敏。



### core_google

来源：DropLAN 当前启用源码；产物：aar；分类：SDK 功能库。

统一 Credential Manager Google 登录、Firebase Authentication、Play Billing 和评分提示。评分参考 SiteReport / PressIPTC，使用通用完成事件、可配置冷却和 Play 原生流程，不做高低星分流。保留 GoogleSignInCoordinator、DropGoogleAccount、GoogleBillingRuntime/Manager 及状态缓存；不依赖基础库，也不引入原页面、广告、Analytics 或 DevHub。商品、账号策略和服务端验证由宿主配置。Play 支持订阅与非消耗型一次性购买，不适用于消耗品。

本地解析 token claims 只供显示，Firebase 验证后才构成 Firebase 会话；客户端购买状态不替代服务端验单。旧分散认证、Billing 和 Entitlement 库保持移出。Demo 提供独立配置、登录、报价选择、购买确认与恢复流程；不自动购买。详见 [接入说明](../core/core_google/README.md)。

2026-10-27 已落地的接口与可靠性调整见 [逐库优化记录](library-optimization.md)。未知订阅状态不降级为未订阅；各库的取消、输出归属和事务边界以该记录与模块 README 为准。
