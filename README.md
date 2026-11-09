# DevLib

Android / JVM 独立功能库、开发者工作台与项目级 Skills。按需复用源码库，也可以把开发协作方法接入其他项目。

| 你要做什么 | 从这里开始 |
| --- | --- |
| 使用 AI 辅助开发、设计和评审 | [Skill 能力与使用](#skill-能力与使用) |
| 查找基础库或独立功能 | [库目录](#库目录) |
| 在其他工程接入库 | [快速接入](#快速接入) |
| 接入安装、日活与设备统计 | [DevHub 基础统计](#devhub-基础统计) |
| 制作与发布产品推广视频 | [推广视频使用指南](doc/promo-video-publisher.md) |
| 运行 Demo、编译或查看验证结果 | [工作台与构建](#工作台与构建) |

## Skill 能力与使用

项目规则与 Skills 位于 `.agents/`，由根 [AGENTS.md](AGENTS.md) 组织任务路由。直接描述需求即可；也可以明确指定 Skill。执行时按任务影响加载相应能力，小改动不需要走完整设计流程。

| 场景 | 对应 Skills | 主要产出 |
| --- | --- | --- |
| 需求与架构 | `clarify-requirements`、`senior-android-engineer`、`android-project-architecture-workflow` | 明确范围、模块边界、基础库复用与实现方案 |
| 工程初始化 | `android-project-scaffold-workflow` | 工程骨架、模块和构建配置 |
| 页面与交互 | `ui-design`、`android-interaction-design`、`android-ui-workflow`、`ui-style-review` | 页面层级、导航与状态、XML 实现及风格审查 |
| 设计稿还原 | `android-figma-workflow`、`zeplin-android-layer-implementation` | 图层、资源、页面实现与还原检查 |
| 国际化与构建 | `android-i18n-workflow`、`android-build-workflow` | 文案与多语言检查、依赖配置、系统兼容与打包验证 |
| 评审与验收 | `engineering-review`、`review-user-journey`、`android-functional-test-workflow`、`acceptance-review` | 代码审查、流程检查、回归报告与验收结论 |
| 统计与推广 | `devhub-basic-integration`、`google-play-aso`、`plan-google-play-listing-assets`、`project-official-website` | 基础统计接入、商店分析与素材方案、产品官网 |
| 推广视频与引流 | `promo-video-publisher` | 项目卖点与关键词分析、真机/桌面实录、英文封面/简介/话题/字幕、平台发布核验 |

**使用示例：**

```text
分析这个项目的模块边界，优先复用已有基础库，再实现定位功能。
优化当前页面的交互与布局，保留已有功能入口，并检查系统栏和键盘适配。
使用 engineering-review 检查本次改动，再根据需求进行回归与验收。
```

**搭配基础库：** Android 页面复用目标项目已有 Base 和公共组件；DevLib 使用 XML + ViewBinding + MVVM。普通项目优先沿用简单导航；大型项目有跨模块解耦、统一拦截或服务发现需求时，再评估 AndroidCoreRouters。使用 Skills 不要求引入全部 DevLib 模块。

**复用到其他项目：** 合并所需 `.agents/` 规则、工作流、Skills 及其引用资源，并按目标工程改写 `AGENTS.md` 和项目档案。包名、模块清单、品牌、设备策略和 UI 基线以目标工程为准；已有 `CLAUDE.md`、`GEMINI.md` 可添加读取统一规范的入口说明。完整步骤见 [Skill 使用与跨项目接入](.agents/README.md)，逐项职责见 [能力索引](.agents/INDEX.md)。

## 库目录

当前 **21 个模块：架构基础 8、独立功能 11、三方服务 1、内部支撑 1**。模块放在 `core/`，逻辑路径为 `:core:<模块名>`；目录映射见 [settings.gradle](settings.gradle)，机器可读清单见 [library-catalog.json](docs/library-catalog.json)。

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

### 独立功能：11 个库

| 模块与说明 | 能力 | 来源 |
| --- | --- | --- |
| [core_archive](core/functional/core_archive/README.md) | ZIP 创建、加密归档与受预算限制的解压。 | PhotoKit |
| [core_image_render](core/functional/core_image_render/README.md) | 水印、标注、样机、相框和签名提取等轻量 Bitmap 渲染。 | PhotoKit |
| [core_document](core/functional/core_document/README.md) | 以同一份记录生成 PDF、Excel、Word，包含资料、签名、模板、水印及历史快照。 | PhotoKit / RoofQuote |
| [core_qrcode](core/functional/core_qrcode/README.md) | 二维码生成、样式渲染、图片识别和扫码 Activity。 | PhotoKit |
| [core_location](core/functional/core_location/README.md) | 系统 GPS/网络/缓存定位、传感器融合与轨迹，宿主管理授权和生命周期。 | LocationKit |
| [core_location_address](core/functional/core_location_address/README.md) | 可选 GeoNames 离线附近地名查询，独立数据库资源。 | LocationKit |
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

LocationKit 的定位和离线地名源码已迁入 DevLib，后续在这里维护；经纬度定位无需第三方地图 Key，离线地名库按需引入。权限、调用示例与 API 37 兼容范围见两库 README，来源与迁移记录见 [LocationKit 说明](doc/locationkit-migration.md)。

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

## DevHub 基础统计

DevHub 在独立私有仓库维护，消费项目直接接入 SDK。它提供安装、日活、手机厂商与型号、Android/App 版本以及 Locale 国家与语言统计；不计入上面的 21 个源码模块。

| 配置项 | 接入要求 |
| --- | --- |
| SDK | 已核对版本 `com.github.wukuiqing49:DevHub:1.2.0` |
| 私有依赖 token | 用户级 Gradle `authToken` 或环境变量 `JITPACK_AUTH_TOKEN`，仅用于构建下载 |
| API 地址 / App Key | 按消费应用配置 HTTPS API 根地址及发行 App Key |
| 统计范围 | 显式设置 `analyticsMode=CORE_ONLY` |
| 机型、国家与语言 | 设置 `collectDeviceInfo=true`、`collectLocale=true`，由 SDK 自动获取 |
| 初始化与采集开关 | 在 Application 初始化一次，由宿主管理有效授权与撤回 |

Locale 国家可能为空，也不代表真实定位国家。SDK 1.2.0 的 `collectLocale` 默认是 false，因此接入时需显式开启。完整仓库配置、Application 示例与错误处理见 [DevHub 功能与配置指南](doc/devhub-integration.md)。

## 工作台与构建

工作台包名为 `com.wkq.dev`，按 **架构、功能、三方、工作区** 四入口组织，提供能力体验与宿主接入示例。使用路径见 [工作台说明](doc/developer-workbench.md) 和 [Demo 指南](doc/test-lab.md)。

```text
app/                         应用壳、Application 与平台配置
core/                        architecture / functional / thirdparty / internal
feature/feature_app/         工作台分类、搜索、导航与工作区
feature/feature_demo_*/      各领域 Demo 与宿主适配
feature/feature_res/         宿主共享资源
.agents/                     项目规则、工作流、Skills 与校验工具
doc/                         接入指南、分析和测试报告
docs/                        模块与来源事实清单
samples/published-consumer/  发布产物独立消费示例
```

core 不依赖 app/feature。宿主负责账号、商品、凭据、服务器、权限解释、后台生命周期与付费策略。各库职责和限制见模块 README，例如 `core_usb` 通过 SAF 访问授权目录，不提供 USB 文件系统驱动。


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

## 文档与维护

- [文档导航](doc/README.md)：专题接入、迁移、架构分析与历史报告。
- [库分类与 Demo 对照](doc/library-organization.md)、[来源清单](docs/import-manifest.json)：当前模块归属与迁入快照。
- [协作规范](AGENTS.md)、[Skill 使用说明](.agents/README.md)、[完整能力索引](.agents/INDEX.md)：项目开发与跨项目复用。
- [测试计划](doc/testing/TEST_PLAN.md)、[测试报告](doc/testing/TEST_REPORT.md)：已执行结果与未验证范围。

模块范围以事实清单为准，来源项目名不表示迁入整个产品。各模块保留原有许可与归属，分发前需核对模块及传递依赖的许可。源码迁入、构建通过和 Git 推送均不代表设备验收或远端产物发布完成。
