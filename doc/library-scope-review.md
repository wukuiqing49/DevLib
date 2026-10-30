# DevLib 功能库范围复核

日期：2026-10-23。本方案已获用户确认并执行。该次裁剪后的目录为 19 个模块：8 个基础模块、10 个功能库、1 个内部支撑库。历史上由 core_nas/core_usb 替换 lib_transfer 后曾为 20 个模块；合并文档库后当前为 19 个，最新分类与路径以 docs/library-catalog.json 为准。下表保留裁剪时的取舍依据。

## 重新明确目标

DevLib 应保留经过筛选、能在不同项目复用的独立开发能力，而不是所有来源项目的源码集合。前面的迁入范围过大，把“能拆成库”当成“应进入 DevLib”，随后又把范围问题误当成去重问题处理。

用户已明确基础库不动，并指出 lib_auth_credentials、lib_auth_firebase、lib_camera_capture 没有当前用途。接下来应以实际需求和维护成本筛选功能库，不以 Demo 已完成或测试数量作为保留理由。

## 基础体系：全部保留，不改造

AndroidCoreBase、AndroidCoreUtils、AndroidCoreNetwork、AndroidCoreRouters 四模块、XPopup，共 8 个模块。它们是既有公共底座，不参与这次功能库删减、合并或重写。

上一轮误入 core_utils.ShareUtil 的修改已逐项撤回，未回滚其他已有工作。

## 全部功能模块的建议

推荐范围是假设 DevLib 聚焦文件流转、轻量图片处理、元数据及通用文档导出；它是当前上下文下的建议，不代表这些方向之外的库没有价值。

| 模块 | 来源 | 建议 | 依据 |
| --- | --- | --- | --- |
| core_archive | PhotoKit | 保留 | 通用压缩与解压，能力范围明确 |
| core_image_render | PhotoKit | 保留 | 轻量 Android 图片渲染、水印和合成，作为当前唯一保留的图片执行路线 |
| core_media_store | PhotoKit | 暂移出 | 包含产物落盘、缓存和系统媒体发布；有通用价值，但目前没有必须维护独立媒体产物层的需求，不应默认纳入 |
| core_pdf | PhotoKit | 保留 | 图片排版转 PDF，边界较清晰，适合作为当前选定的 PDF 能力 |
| core_qr_transfer | PhotoKit | 暂移出 | Nearby 连接、配对、会话及传输另成体系；当前没有明确的双设备 Nearby 需求 |
| core_qrcode | PhotoKit | 保留 | 二维码编解码，相对独立；不必同时携带 Nearby 传输 |
| id-photo-library | PhotoKit | 移出 | 证件照规格属于明确领域能力，当前没有证件照产品需求 |
| lib_auth_credentials | DropLAN | 移出 | 用户明确不需要；账号接入由消费产品决定 |
| lib_auth_firebase | DropLAN | 移出 | 用户明确不需要；且依赖 Credentials，不应默认绑定 Firebase |
| lib_billing_play | NASFileManager | 暂移出 | Play 商品、购买和账号校验有具体产品前提，目前不是必要开发能力 |
| lib_camera_capture | SiteReport | 移出 | 用户明确不需要；还 api 依赖定位库，会带入更多配套能力 |
| lib_crash | AppCrash | 移出 | 主动触发崩溃的测试工具，不是普通功能库；原项目继续保留即可 |
| lib_document | RoofQuote | 保留 | 轻量 DOCX/XLSX 写入。实际构建是 Kotlin/JVM，不是 Android AAR 或 Gradle 插件 |
| lib_drop | DropLAN | 保留 | 局域网 HTTP 文件流转具有独立用途；仅保留服务能力，不默认迁入原产品 UI |
| lib_entitlement | DevLib | 移出 | 当前没有统一账号、服务端校验与付费能力需求，单独的权益判定抽象没有接入闭环 |
| lib_html | DropLAN | 暂移出 | 原离线接收网页是具体前端实现，不应作为与通用传输并列的必备库；移出后完整浏览器演示也需调整 |
| lib_location | SiteReport | 暂移出 | 无明确定位需求，且带有 SDK Key、隐私初始化和宿主配置成本 |
| lib_metadata | PressIPTC | 保留 | EXIF/IPTC/XMP 专用读写，是独立且较难替代的能力；包含 Native 产物，仍需单独核验格式与 ABI 范围 |
| lib_operations | PhotoKit | 内部依赖保留 | 多个拟保留库依赖预算、取消、进度及原子输出契约，不能直接删除；不作为独立业务功能宣传 |
| lib_report_pdf | SiteReport | 暂移出 | 报告、检查、证据、签名等模板带有 SiteReport 领域特征；当前 PDF 只保留 core_pdf，不因用途不同就两套都纳入 |
| lib_transfer | NASFileManager | 保留 | 多存储文件操作与传输，适合作为唯一 SMB/SFTP/WebDAV 实现 |
| lib_upload | PressIPTC | 保留 | 服务器上传仍有独立用途；当前仅 FTP/FTPS/HTTP。此前裁掉协议是去重改动，不能将其当成功能范围已经确认 |
| phototoolkit-engine | PhotoKit | 暂移出 | 同时包含 libvips、证件照、人像检测、抠图、质量检查及模型配送，远超轻量通用图片库范围 |
| phototoolkit-raw | PhotoKit | 暂移出 | 专业 RAW 解码依赖 Native 图片引擎，没有明确 RAW 场景不必迁入 |
| portrait-model-assets | PhotoKit | 移出 | 为人像管线提供模型配送；移出引擎后没有保留理由，不应继续注册到 App |

按此建议：8 个基础模块 + 9 个对外功能库 + 1 个功能内部支撑库，共 19 个模块；从当前范围移出 15 个功能模块。该模块范围已落实；具体验证结果见测试报告。

## PhotoKit 应如何取舍

当前 PhotoKit 来源共有 11 个模块。推荐只保留 core_archive、core_image_render、core_pdf、core_qrcode 四项明确能力，以及它们依赖的 lib_operations；其余 6 项移出当前主工程。

特别是 phototoolkit-engine 不只是图片压缩工具：构建中直接引入 LiteRT、ML Kit 人脸/面网格/姿态检测和 Play Asset Delivery，源码包含证件照、人像几何、抠图及合规检查。移进来会把专业产品链一起带入。

本地 src/main 下 Native/模型文件原始大小统计：引擎约 110.3 MiB，RAW 约 8.6 MiB，模型包约 25.0 MiB。这是多 ABI 源文件统计，不是 APK 下载体积，也未包含全部依赖或 prebuilt；只用于说明维护范围，不能直接相加宣称最终包体节省。

core_media_store 并非没有价值，但不应仅因同属 PhotoKit 就一起保留。它不属于 AndroidCore 基础库；移出不需要修改基础库。以后确实需要系统媒体发布时，再按具体需求引入。

## 保留后的业务入口

- 文件：压缩解压、局域网分享、服务器上传、多存储传输。
- 图片：轻量渲染/水印、二维码、元数据。
- 文档：图片 PDF、DOCX/XLSX 导出。

基础能力示例与以上功能入口分开；lib_operations 是内部支撑，不再为凑“一模块一业务页面”单独制造页面。

## 执行边界

1. 先固定保留清单，再处理 settings、依赖、Manifest、资源、Demo、版本目录和文档；不要继续边写 Demo 边扩大模块范围。
2. 先移出用户明确不需要的两个登录库和拍照库，再按候选清单裁剪其余配套能力。
3. 删除人像链时同步取消 App 的 portrait-model-assets 注册与相关初始化；删除相机/定位时同步处理权限、Key 和页面入口。
4. 拟保留库对拟移出模块的 Gradle project 依赖已检查：这 10 个保留功能模块没有直接 project 依赖指向移出清单。该检查不替代反射、资源、Manifest、服务注册及运行期引用核验。
5. lib_drop 与 lib_html 当前互不依赖，但完整浏览器流程的前端资源需要重新安排；不能删除网页后仍声称浏览器演示完整。
6. 不因删除独立分享库就继续向 Utils 增加能力；不因为要保留一个 PDF 方向就改写基础库。

本方案已完成模块、依赖与 Demo 裁剪。分析依据为当前源码、模块构建、App 配置和来源清单；未执行设备测试。

## 后续确认：统一 Google SDK

用户随后明确采用 DropLAN 的 core_google，新增为第 10 个功能库。上述旧分散认证、Billing、Entitlement 的移出结论保持有效；由一个 core_google 统一承载 Google 登录、Firebase Auth 与 Play Billing。只迁入来源项目已启用的代码，不恢复原 UI、广告、Analytics、DevHub 或商品策略。基础库保持原样。
