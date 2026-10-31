# DevLib

Android / JVM 独立开发库源码仓库与开发者工作台，App 包名为 `com.wkq.dev`。当前 **19 个模块：8 个架构基础模块、9 个独立功能库、1 个三方服务适配库、1 个内部支撑库**。

本仓库保留库的实际源码与资源，工作台提供独立能力流程和接入参考。基础库沿用原实现及公开包名；底层 AndroidX、协议客户端与 Google SDK 等第三方依赖仍通过 Maven 获取。源码迁入不等于设备或生产验收完成。

## 库目录

物理目录按职责分组，Gradle 逻辑路径保持 `:core:<模块名>`。四个分组目录自身不是模块，映射以 [settings.gradle](settings.gradle) 为准。**同名 core_ 前缀不代表基础库**；XPopup 属于既有公共底座，NAS/USB 是功能库，Google 是平台服务适配。

### 架构基础：8 个模块、5 套体系

| 模块与说明 | 能力 | 来源 |
| --- | --- | --- |
| [core_base](core/architecture/core_base/README.md) | Activity、Fragment、ViewModel、Adapter、标题栏、页面状态与弹窗基础封装。 | AndroidCoreBase |
| [core_network](core/architecture/core_network/README.md) | Retrofit、OkHttp、统一网络响应，以及上传进度和下载封装。 | AndroidCoreNetwork |
| [core_router_annotation](core/architecture/core_router_annotation/README.md) | 路由、参数、拦截器和服务注解及生成代码共享契约。 | AndroidCoreRouters |
| [core_router_api](core/architecture/core_router_api/README.md) | Android 路由运行时、参数传递、拦截器、服务与降级处理。 | AndroidCoreRouters |
| [core_router_gradle_plugin](core/architecture/core_router_gradle_plugin/README.md) | 配置路由运行时、KSP 处理器依赖与 moduleName 的 Gradle 插件。 | AndroidCoreRouters |
| [core_router_processor](core/architecture/core_router_processor/README.md) | KSP 路由扫描与注册/分组/参数代码生成器。 | AndroidCoreRouters |
| [core_utils](core/architecture/core_utils/README.md) | 文件、Uri、选择器、图片加载、存储、设备与系统工具。 | AndroidCoreUtils |
| [lib_xpopup](core/architecture/lib_xpopup/README.md) | 公共弹窗、底部弹层、抽屉与图片查看组件。 | XPopup |

### 独立功能：9 个库

| 模块与说明 | 能力 | 来源 |
| --- | --- | --- |
| [core_archive](core/functional/core_archive/README.md) | ZIP 创建、加密归档与受预算限制的解压。 | PhotoKit |
| [core_image_render](core/functional/core_image_render/README.md) | 水印、标注、样机、相框和签名提取等轻量 Bitmap 渲染。 | PhotoKit |
| [core_document](core/functional/core_document/README.md) | 以同一份记录生成 PDF、Excel、Word，包含资料、签名、模板、水印及历史快照。 | PhotoKit / RoofQuote |
| [core_qrcode](core/functional/core_qrcode/README.md) | 二维码生成、样式渲染、图片识别和扫码 Activity。 | PhotoKit |
| [lib_drop](core/functional/lib_drop/README.md) | 局域网只读文件分享、配对审批、HTTP/HTTPS/Range 和 WebSocket 传输。 | DropLAN |
| [lib_metadata](core/functional/lib_metadata/README.md) | EXIF/IPTC/XMP、sidecar、隐私清理、格式校验与元数据模板。 | PressIPTC |
| [lib_upload](core/functional/lib_upload/README.md) | FTP、FTPS、HTTP 上传客户端，以及配置、凭据与进度管理。 | PressIPTC |
| [core_nas](core/functional/core_nas/README.md) | SMB、WebDAV、SFTP 独立连接会话、目录浏览及流式读写。 | NASFileManager |
| [core_usb](core/functional/core_usb/README.md) | 通过 Android SAF 访问用户授权的文档树和可移动存储。 | NASFileManager |

### 三方服务适配：1 个库

| 模块与说明 | 能力 | 来源 |
| --- | --- | --- |
| [core_google](core/thirdparty/core_google/README.md) | Google 登录、Firebase Auth、Play Billing、原生支付/兑换/恢复与评分提示。 | DropLAN / SiteReport / PressIPTC |

### 内部支撑：1 个库

| 模块与说明 | 能力 | 来源 |
| --- | --- | --- |
| [lib_operations](core/internal/lib_operations/README.md) | 操作预算、协作式取消、结果、进度及原子输出的内部公共契约。 | PhotoKit |

`lib_operations` 不要求单独业务页面。Google 登录、Firebase Auth、Play Billing 与评分统一采用 `core_google`，不再维护旧分散授权库。Google 原生支付 Activity、BottomSheet、官方兑换、恢复及状态流在同一个库中；商品 ID、账号、条款、业务 Pro 映射由宿主提供。

## 快速接入

同仓消费在模块的 dependencies 中按需添加源码依赖，例如：

```groovy
implementation project(':core:core_network')
implementation project(':core:core_document')
```

独立项目使用发布产物；标准坐标为 `com.wkq.dev:<模块名下划线替换为连字符>:<版本>`，Base、Utils、XPopup 分别使用 `1.3.1`、`1.1.2`、`3.3.2`（模块 `version.properties`）；其他库默认 `0.1.0-SNAPSHOT`（根 [build.gradle](build.gradle) 的 `devlibVersion`）。不要同时引入同库源码与旧 AAR/JAR，也不要只复制缺失传递依赖的孤立 AAR。

```powershell
# 在本仓根目录发布完整依赖和路由插件 marker，输出 build/repo
.\gradlew.bat publishLibrariesToLocalRepository
```

消费工程在 settings.gradle 的 dependencyResolutionManagement.repositories 添加文件 Maven 仓库后，使用：

```groovy
implementation 'com.wkq.dev:core-network:0.1.0-SNAPSHOT'
```

路由生成器使用 `ksp`，路由插件通过 plugins 应用，并在 pluginManagement.repositories 配置发布仓库；详见各模块 README 和 [独立消费示例](samples/published-consumer/README.md)。网络库另外提供通用 Maven、GitHub Packages 与 JitPack 入口，见 [发布指南](core/architecture/core_network/docs/core_network_publish.md)；其他模块当前默认本地发布。真实凭据只使用用户级配置或环境变量，不进入仓库。

## 工作台与宿主边界

App 按 **架构、功能、三方、工作区** 四入口组织，支持导入、配置、执行、结果预览、保存与跨工具流转；每个库的 README 链接到对应 Demo 调用代码。路由体系以四个子模块说明编译期与运行期分工，内部支撑由上层功能集成覆盖。

```text
app/                         Application、平台声明与工作台宿主
core/architecture/           8 个架构基础模块
core/functional/             9 个独立功能库
core/thirdparty/             core_google 平台服务适配
core/internal/               lib_operations 内部支撑
feature/feature_app/         分类、搜索、导航与工作区
feature/feature_demo_*/      逐领域业务体验与宿主适配
feature/feature_res/         宿主共享资源
.agents/                     项目规则、工作流、Skills 和本地验证工具
doc/                         使用说明、分析与测试报告
docs/                        模块分类和来源事实清单
samples/published-consumer/  发布产物独立消费示例
```

core 不依赖 app/feature。宿主负责账号、商品、凭据、服务器、权限解释、授权目录、后台生命周期及业务付费策略。页面使用现有 Base、XML + ViewBinding + MVVM；消费项目仍需检查国际化、系统栏/IME/刘海 Insets 及适用场景的沉浸式。

NAS 承担 SMB/SFTP/WebDAV，lib_upload 承担 FTP/FTPS/HTTP 上传，lib_drop 承担局域网只读分享；core_usb 使用 SAF，**不提供 USB 文件系统驱动**。记录导出使用 core_document，一份记录生成 PDF/Excel/Word，包含资料、签名图片、模板、水印与历史快照；不重新引入旧分散文档模块。

## 构建与验证

根工具链：JDK 17、Gradle 9.3.1、AGP 9.1.1、Kotlin 2.2.10；工作台 minSdk 26、compileSdk/targetSdk 37。各库的声明下限和 JVM target 见自己的 README 与 build.gradle，**不能把 App 配置当成所有库配置**：例如 XPopup 保留 compileSdk 36 / minSdk 21 / JVM target 1.8。实际消费下限还需考虑传递依赖。

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat assembleLibraries
.\gradlew.bat testLibraries
python -B .agents/scripts/validate_agent_structure.py --project-root .
python -B scripts/test_core_layout.py -v
python -B scripts/verify_demo_catalog.py --catalog-only
```

以上是本地验证入口；逐项实际结果、日期与未验证范围以 [固定测试报告](doc/testing/TEST_REPORT.md) 为准。历史结果不代表本轮重跑或所有设备通过。默认不执行 adb/真机，不自动创建或调整 GitHub Actions；远端发布和 JitPack 服务端构建需单独验收。Android 17 适配范围见 [兼容说明](doc/android17-compatibility.md)。

## 范围与文档

不引入 Starxen、BeautySDK、DevHub；已移出的分散登录/支付权益、拍照定位、Nearby、原产品网页、证件照、人像、RAW 与模型配送不在当前范围。来源项目名只是溯源，不表示迁入整个产品；具体保留清单见 [library-catalog](docs/library-catalog.json)，来源及移出记录见 [import-manifest](docs/import-manifest.json)。

- [文档导航](doc/README.md)：专题说明、接入与验证入口。
- [core 目录与分类约定](core/README.md)、[分类与页面对照](doc/library-organization.md)。
- [工作台流程](doc/developer-workbench.md)、[Demo 使用](doc/test-lab.md)。
- [统一记录导出](doc/record-export.md)、[NAS/USB 接入](doc/nas-usb-migration.md)、[逐库可靠性边界](doc/library-optimization.md)。
- [测试计划](doc/testing/TEST_PLAN.md)、[测试报告](doc/testing/TEST_REPORT.md)。
- [协作规范](AGENTS.md)：修改源码、基础复用、中文注释、国际化与适配要求。

各模块保留其原有许可及归属；来源记录不替代许可审查，分发前按模块及传递依赖核对许可要求。

## Agent 开发协作

规范入口为 [AGENTS.md](AGENTS.md)，能力导航见 [.agents/INDEX.md](.agents/INDEX.md)，使用方法见 [.agents/README.md](.agents/README.md)。实际目录为 `.agents/`，不维护同内容的 `.agent/` 或 `Agent.md` 副本。

直接描述需求即可，无需手动指定 Skill 名称。Agent 根据任务选择需求澄清、Android 架构与开发、UI 设计、工程评审、用户路径和验收方法；页面任务遵循本产品设计基线，纯网络引擎不强制启动设计流程。构建和本地测试结果不等于设备、视觉或远端发布验收。
