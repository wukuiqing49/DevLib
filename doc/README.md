# DevLib

独立 Android/JVM 功能库主仓库。所有能力放在 `core/`，应用包名 `com.wkq.dev` 用于集成演示；按需依赖各模块，不强制打包整套库。

各库用途、公开入口、依赖关系、测试覆盖及后续建议见 [逐库分析](library-analysis.md)。

本文件是项目 README；所有命令均在仓库根目录执行。

## 工程结构

```text
DevLib/
├── app/                       # 演示应用壳
├── core/                      # 44 个独立库及 1 个模型资源包
├── feature/                   # 演示页面和共享资源
├── samples/published-consumer/ # 独立发布产物消费样例
├── gradle/                    # 工具链、依赖目录和发布配置
├── doc/                       # 项目说明、库分析和测试记录
├── docs/                      # 来源清单及结构化分析
└── .agents/                   # 开发协作规则及技能
```

## 模块目录

| 模块 | 能力 | 来源 |
| --- | --- | --- |
| `core_archive` | 压缩与归档 | PhotoKit |
| `core_base` | Activity、Fragment、ViewModel、Adapter 与窗口基础能力 | AndroidCoreBase |
| `core_image_render` | 图片排版与渲染 | PhotoKit |
| `core_media_store` | 媒体记录与 MediaStore 文件访问 | PhotoKit |
| `core_network` | Retrofit、OkHttp、协程与统一网络结果 | AndroidCoreNetwork |
| `core_pdf` | 图片与页面 PDF 输出 | PhotoKit |
| `core_qr_transfer` | Nearby 近距文件传输与扫码配对 | PhotoKit |
| `core_qrcode` | 二维码生成与识别 | PhotoKit |
| `core_router_annotation` | 路由注解与生成契约 | AndroidCoreRouters |
| `core_router_api` | Android 路由运行时 | AndroidCoreRouters |
| `core_router_gradle_plugin` | 路由 Gradle 配置插件 | AndroidCoreRouters |
| `core_router_processor` | KSP 路由代码生成 | AndroidCoreRouters |
| `core_share` | 系统文件分享入口 | PhotoKit |
| `core_utils` | 通用文件、存储、Coil、MMKV 与设备工具 | AndroidCoreUtils |
| `id-photo-library` | 证件照规格、证据与版本数据安装 | PhotoKit |
| `lib_agora` | Agora RTC 媒体适配 | Starxen |
| `lib_auth_google` | Google ID-token 登录 | Starxen |
| `lib_beauty` | 美颜帧处理、渲染及会话契约 | BeautySDK |
| `lib_beauty_mediapipe` | MediaPipe 人脸分析适配 | BeautySDK |
| `lib_beauty_rfb` | ONNX/RFB 人脸检测适配 | BeautySDK |
| `lib_billing_play` | Google Play 商品、购买、恢复与购买状态 | NASFileManager |
| `lib_camera` | CameraX 帧源适配 | BeautySDK |
| `lib_camera_capture` | CameraX 拍摄与位置适配 | SiteReport |
| `lib_crash` | Java/Kotlin/Native 故障测试 | AppCrash |
| `lib_devhub` | DevHub 客户端 SDK | DevHub |
| `lib_document` | 轻量 DOCX/XLSX OpenXML 写入 | RoofQuote |
| `lib_drop` | LAN 分享、配对、HTTP/HTTPS/WebSocket 下载 | DropLAN |
| `lib_entitlement` | 平台无关、账号隔离的权益有效性判断 | DevLib |
| `lib_html` | 离线浏览器接收网页 | DropLAN |
| `lib_live_api` | 直播媒体接口与状态契约 | Starxen |
| `lib_location` | 位置获取、离线地理编码与数据导入 | SiteReport |
| `lib_metadata` | EXIF/IPTC/XMP 读写、容器处理与通用模板模型 | PressIPTC |
| `lib_operations` | 图片操作预算、取消、结果、进度及原子输出契约 | PhotoKit |
| `lib_push_fcm` | FCM 消息、通知与点击分发 | Starxen |
| `lib_report_pdf` | 可配置报告数据的 PDF 排版 | SiteReport |
| `lib_transfer` | 本地/SAF/SMB/WebDAV/SFTP、持久任务与同步 | NASFileManager |
| `lib_upload` | FTP/FTPS/SFTP/WebDAV/HTTP/SMB 上传 | PressIPTC |
| `lib_xpopup` | 弹窗、抽屉、图片查看器 | XPopup |
| `phototoolkit-engine` | libvips/JNI 图片处理及人像处理 | PhotoKit |
| `phototoolkit-raw` | RAW 解码与图片引擎适配 | PhotoKit |
| `portrait-model-assets` | 可选人像模型资源包 | PhotoKit |
| `lib_auth_credentials` | Credential Manager Google 登录 | DropLAN |
| `lib_auth_firebase` | Firebase 认证适配 | DropLAN |
| `lib_live_beauty` | 腾讯纹理美颜适配 | Starxen |
| `lib_tencent_beauty_kit` | 腾讯美颜 Kit 封装 | Starxen |

## 构建与发布

使用 JDK 17、Android SDK 36、AGP 8.13.2、Gradle 8.13、Kotlin 2.1.21、KSP 2.1.21-2.0.1。Native 模块需要其中央依赖快照声明的 NDK/CMake。

本机需通过 `ANDROID_HOME` 或被忽略的 `local.properties` 配置 Android SDK。

```powershell
.\gradlew.bat projects
.\gradlew.bat assembleLibraries :app:assembleDebug
.\gradlew.bat testLibraries
.\gradlew.bat publishLibrariesToLocalRepository
```

单个 Android 库可独立执行 `:core:<模块>:assembleRelease` / `publishReleasePublicationToDevLibLocalRepository`。JVM 模块构建 `jar`；路由 Gradle 插件使用 `:core:core_router_gradle_plugin:publishAllPublicationsToDevLibLocalRepository`，同时发布插件 marker。发布输出在 `build/repo`，包含各库 POM 与 sources；配置不向外部仓库发布。

同仓项目：

```groovy
implementation project(':core:lib_drop')
implementation project(':core:lib_upload')
ksp project(':core:core_router_processor')
```

跨工程使用本地发布产物：

```groovy
repositories {
    google()
    mavenCentral()
    maven { url = uri('<DevLib绝对路径>/build/repo') }
    maven { url = uri('https://jitpack.io') }
}
dependencies {
    implementation 'com.wkq.dev:lib-drop:0.1.0-SNAPSHOT'
}
```

依赖快照集中放在 `gradle/catalogs/`，插件工具链统一在 `gradle/libs.versions.toml`；库发布坐标在 `gradle/library-publish.gradle`。保留原库 API 包名，避免消费方全量改 import。

## 迁入状态

- 本次为源代码迁入与独立构建接入；原项目未被修改，尚未切换到 DevLib 依赖。
- 来源提交、工作区改动与初始 SHA-256 清单在 `docs/import-manifest.json`。
- 登录和推送分包；支付只取 NAS 的核心基线，七份副本的业务修复仍需逐项比较，不能视为全部合并。
- NAS 不再引用宿主 ProBilling、feature 资源或固定启动 Activity；高级能力策略及通知路由由宿主注入。
- 元数据使用独立 MetadataRecord DTO，照片数据库仍留在业务 App。
- Play Asset Pack 单独管理，不作为 AAR 发布；Native、人像模型、RTC、支付和真实服务器能力需要单独运行期验证。
- 构建、测试及未验证项见 [测试报告](testing/TEST_REPORT.md)。

## 发布消费验证

`samples/published-consumer` 是独立 Gradle 工程，只消费发布产物，不使用本仓库 project()。先发布，再执行：

```powershell
.\gradlew.bat -p samples/published-consumer compileDebugKotlin
```

路由插件、注解、processor 和运行时独立发布；同仓通过 `implementation project(':core:core_router_api')`、`ksp project(':core:core_router_processor')` 接入。

## 运行演示应用

手机开启 USB 调试并授权电脑后，在仓库根目录执行：

```powershell
.\gradlew.bat :app:assembleDebug
adb devices -l
adb -d install -r app/build/outputs/apk/debug/app-debug.apk
adb -d shell am start -W -n com.wkq.dev/.MainActivity
```

`-d` 选择 USB 真机；连接多台真机时改用 `-s <设备序列号>`。演示应用目前接入基础页面，全部功能库的演示页面尚未完成。第三方账号、支付、RTC、美颜授权及模型配送由具体宿主配置。

## 分析与测试文档

| 文档 | 内容 |
| --- | --- |
| [逐库分析](library-analysis.md) | 45 个模块的职责、入口、依赖、限制及后续建议 |
| [来源清单](../docs/import-manifest.json) | 来源提交、初始文件摘要与迁入调整 |
| [结构化分析](../docs/library-analysis.json) | 机器可读的逐库分析数据 |
| [测试计划](testing/TEST_PLAN.md) | 验证范围、用例与命令 |
| [测试报告](testing/TEST_REPORT.md) | 已执行结果、证据与未验证项 |
| [测试基线](testing/TEST_BASELINE.json) | 源码指纹、环境和逐项验证记录 |

## 开发约定

- 新增可复用能力放在职责匹配的 `core` 模块；业务编排和页面放在宿主或演示 feature。
- 库不依赖 App/业务 feature，公开 API 涉及的依赖应正确使用 `api` 暴露。
- 只按需依赖模块，避免同时引入同一能力的本地源码和旧 Maven AAR。
- 不提交本机 SDK 路径、真实凭据、签名文件及构建缓存。
- 修改库时同步接入说明和必要验证；项目协作规范见 [AGENTS.md](../AGENTS.md)。
