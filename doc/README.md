# DevLib

Android 独立开发库与开发者能力工作台，包名 `com.wkq.dev`。当前保留 **19 个模块：8 个基础模块、10 个功能库、1 个内部支撑库**。App 以功能库、基础组件、工作区三个一级入口组织使用流程，保留 18 个库能力路由（目录展示 10 个功能库、5 套基础体系），记录页内另保留图片 PDF 工具。

## 基础体系

基础库保持原有实现。

| 体系 | 模块 | 用途 |
| --- | --- | --- |
| AndroidCoreBase | `core_base` | Activity / Fragment / ViewModel / Adapter / 标题 / 状态 / Dialog |
| AndroidCoreUtils | `core_utils` | 文件、Uri、选择器、Coil、存储与设备工具 |
| AndroidCoreNetwork | `core_network` | Retrofit / OkHttp / 协程与统一响应 |
| AndroidCoreRouters | `core_router_annotation`、`core_router_api`、`core_router_processor`、`core_router_gradle_plugin` | 路由契约、运行时与构建期生成 |
| XPopup | `lib_xpopup` | 公共弹窗底层，业务优先使用 Base 的 DialogKit |

## 功能库

| 模块 | 来源 | 用途 |
| --- | --- | --- |
| `core_google` | DropLAN | Google 登录、Firebase Auth、Play Billing、原生支付 Activity/BottomSheet、官方兑换与评分提示统一 SDK 适配；[接入说明](../core/core_google/README.md)。 |
| `core_archive` | PhotoKit | ZIP 创建与带密码归档解压，包含压缩等级、加密选项、预算、取消及原子输出。 |
| `core_image_render` | PhotoKit | Bitmap 渲染：批注/撤销历史、水印、设备样机、拍立得相框和签名提取。 |
| `core_document` | PhotoKit / RoofQuote | 同一份记录导出 PDF、Excel、Word；保留原图片 PDF、DOCX/XLSX 写入 API。[接入说明](../core/core_document/README.md)。 |
| `core_qrcode` | PhotoKit | 二维码内容模型、绘制样式、Logo/背景处理、生成/识别和 Camera 扫码 Activity。 |
| `lib_drop` | DropLAN | LAN 浏览器分享引擎：文件源、HTTP/HTTPS、Range、配对审批、下载队列、地址发现和连接建议。 |
| `lib_metadata` | PressIPTC | EXIF/IPTC/XMP 读写、sidecar、隐私清理、格式策略、校验及模板模型；含原生容器处理保护服务。 |
| `core_nas` | NASFileManager | SMB、WebDAV、SFTP 独立会话及流式读写。 |
| `core_usb` | NASFileManager | SAF 授权目录查询及读写，不提供 USB 驱动。 |
| `lib_upload` | PressIPTC | 上传客户端：FTP、FTPS、HTTP（SMB、SFTP、WebDAV 统一使用 core_nas），支持进度、连接检测、配置/凭据存储。 |

`lib_operations` 为功能内部支撑，提供预算、取消、进度、错误和原子输出契约，不单独作为业务功能展示。PhotoKit 来源只保留压缩、轻量图片、图片 PDF、二维码及此支撑库。

原分散登录与支付权益库、拍照定位、主动崩溃、原产品网页、报告模板、Nearby、媒体管理、证件照、Native 图片引擎、RAW 和人像模型已移出。Starxen、BeautySDK、DevHub 不在当前范围。

Google 登录、Firebase Auth 与 Play Billing 统一采用 `core_google`，旧分散模块不再维护。配置和商品由宿主提供，Demo 不自动发起登录或购买。支付页面、状态流及多语言资源均在同一个 core_google 中，直接复用运行时配置；接入示例见库说明。

## 目录与使用

```text
app/                           应用初始化、平台声明与工作台宿主
core/                          基础库、功能库与内部支撑
feature/feature_app            分类、搜索与导航
feature/feature_demo_common    现有 Base 页面、导入、状态与预览
feature/feature_demo_foundation 基础体系示例
feature/feature_demo_files     压缩、上传、传输与局域网分享
feature/feature_demo_image     轻量图片、元数据与二维码
feature/feature_demo_document  统一记录三格式导出与图片 PDF
feature/feature_demo_google    Google 登录、Firebase、Play 购买与评分
doc/                           使用、范围与验证文档
docs/                          分类和来源事实
```

局域网分享使用 Demo 自带的最小接收页，不再依赖独立网页库。远程上传和文件传输需要使用者配置服务器与凭据。库源码不依赖演示页面，凭据和产品策略由宿主维护。

## 演示与本地验证

演示已加入操作记录与远程目录浏览、逐个配对审批、Google 前台购买状态同步，以及五种图片处理流程。完整操作和边界见 [Demo 说明](test-lab.md)。基础库保持原样。

模块产物分类、App/AndroidTest APK 构建、JVM 测试、APK 路由、本地发布和独立消费编译均通过本地命令验证，具体命令与结果见 `testing/TEST_REPORT.md`。本次未经明确需求新增的 GitHub Actions 工作流已撤回，未提交、未推送、未在远端生效。后续仅在用户明确要求 CI 时配置。

## 构建

JDK 17，Gradle Wrapper 与 SDK 以当前配置为准：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat assembleLibraries
.\gradlew.bat testLibraries
.\gradlew.bat publishLibrariesToLocalRepository
```

库坐标为 `com.wkq.dev:<模块名下划线替换为连字符>:0.1.0-SNAPSHOT`，本地仓库输出 `build/repo`。Routers 插件 ID 为 `com.wkq.router`。不同时引入同库源码和旧 AAR。

## 文档

- [功能范围与取舍](library-scope-review.md)
- [逐库分析](library-analysis.md)
- [演示说明](test-lab.md)
- [架构边界](architecture-review.md)
- [当前分类](../docs/library-catalog.json)与[来源/移出记录](../docs/import-manifest.json)
- [测试计划](testing/TEST_PLAN.md)、[最新结果](testing/TEST_REPORT.md)
- [之前的去重分析](duplicate-capability-analysis.md)：历史背景，不作为当前保留清单。

按用户要求不进行真机测试；构建与静态校验不代表服务器、浏览器、扫码或 Native 功能已验收。

## 开发者工作台

App 已接入统一素材与产物记录、收藏与最近使用、操作历史及跨工具流转。文档使用单元格编辑，PDF 支持素材排序，元数据页提供字段详情。见 [产品结构、流程与边界](developer-workbench.md)。

Android 17 / API 37 构建基线、Skill 配置及未验证边界见 [Android 17 兼容说明](android17-compatibility.md)。

- [Agent 与 Skills 整体分析及优化](agent-skill-review.md)：规则分层、校验边界及当前文档/国际化欠账。

- [稳定性与业务英文覆盖](stability-and-i18n.md)：历史收尾、整批素材校验、英文资源与签名验证边界。

- [NAS / USB 接入与测试](nas-usb-migration.md)：轻量独立库、宿主业务流程与退役队列边界。

逐库可靠性优化与边界：[优化记录](library-optimization.md)。实际验证见固定测试报告。

- [页面分析与视觉优化](ui-page-review.md)：工作台与 19 个能力入口的颜色、主次操作、表单、结果及窗口适配。

- [统一记录导出](record-export.md)：模块合并、同一记录三种文件、事务和验证边界。
