> 历史记录：当前来源范围已收紧，Starxen、BeautySDK 和 DevHub 均移出。旧数量、停用开关和测试结果不代表当前构建；以项目 README 和最新测试报告为准。

# DevLib 首次全量迁入验证报告

> 历史范围说明：本记录生成时包含 DevHub；该模块现已移出。美颜开关记录中的旧模块总数也对应移除前状态。当前范围见 [架构分析](../architecture-review.md) 和后续验证记录。

时间：2026-10-23T13:17:46+08:00（Asia/Singapore）。模式：首次全量；分支 main，仓库尚无 HEAD，源码均为未提交工作区。无历史通过基线。

工作区源码/构建输入 SHA-256：`72a560f0a6b017b57818ff358f356d6182531b5b2c5bd06179c1f25429bb8764`（2473 个输入文件，排除 build、缓存、本机 SDK 配置和密钥）。逐用例基线见 TEST_BASELINE.json。

## 范围与结论

`core/` 共 45 个模块：39 个 Android AAR、5 个 JVM JAR、1 个 Play Asset Pack。五套基础库及本次评估的全部功能库已迁入并注册。

44 个库全部完成 debug 构建、release/JVM 本地 Maven 发布，包含 POM 与 sources；路由插件包含独立 marker。示例 App 构建通过。独立消费工程仅用发布坐标，编译通过，并实际执行路由 KSP 生成及生成类编译。

共 785 项既有/新增 JVM 测试：**784 通过、0 失败、1 跳过**。测试分布和逐套件数据见 [module-results.json](evidence/2026-10-23-import/module-results.json)，JUnit 精简证据见 [junit-results.xml](evidence/2026-10-23-import/junit-results.xml)。无测试源码的库仅验证构建与发布，不判定其运行功能通过。

## 环境及产物

- Windows、JDK 17、Gradle 8.13、AGP 8.13.2、Kotlin 2.1.21、KSP 2.1.21-2.0.1、Android SDK 36；Native 按模块工具链执行构建。
- APK：`app/build/outputs/apk/debug/app-debug.apk`；com.wkq.dev，debug，versionCode 1000，versionName 0.1.0，minSdk 26，9323359 字节。
- APK SHA-256：`4353b73eef535e6d3e67605406a3dd316e22f7c08dc4e35fa09764799e98c88b`。
- 设备只做 adb 列表读取，未安装 APK、未执行设备/视觉测试；API、主题、字体、语言和方向矩阵未验证。
- 所有 Maven 产物在 `build/repo`，未向远端仓库发布；未修改原业务工程、未提交或推送。

## 执行结果

| 用例 | 状态 | 执行与实际结果 | 证据/耗时 |
| --- | --- | --- | --- |
| STRUCTURE | 通过 | Agent、构建、架构校验退出码 0；core 不依赖 app/feature | commands.txt；构建 0 警告，架构 2 提示 |
| BUILD-CORE | 通过 | assembleLibraries，39 AAR + 5 JAR | 最终与 App 合并构建 8 秒 |
| BUILD-APP | 通过 | :app:assembleDebug，包名 com.wkq.dev | summary.json；合并构建 8 秒 |
| TEST-FOUNDATION | 通过（已有测试） | base/utils/network/XPopup、路由插件与 processor | JUnit XML；全量测试合并 1 分 10 秒 |
| TEST-COMMERCIAL | 通过（已有测试） | 支付恢复/缓存、账号隔离权益策略、FCM 单元测试 | 同上；认证运行期另列缺口 |
| TEST-FILES | 通过（已有测试） | Drop/HTML/归档/传输；含通知路由及宿主高级能力回调 | 同上；上传库无 JVM 测试 |
| TEST-IMAGES | 通过（已有测试） | 元数据、图片预算/渲染/RAW/二维码/MediaStore/证件照 | 同上；元数据原有 1 跳过 |
| TEST-DOCUMENT | 通过（已有测试） | DOCX/XLSX/PDF 与报告排版 | 同上 |
| TEST-MEDIA | 通过（已有测试） | 美颜契约/RFB/相机、Crash、DevHub | 同上；RTC/商业美颜运行期未验证 |
| PUBLISH | 通过 | 44 库独立 POM/sources/AAR 或 JAR；插件 marker 正确 | 全量 4 分 2 秒；修改库随后重发；最终插件 5 秒 |
| CONSUMER | 通过 | 外部工程 compileDebugKotlin；无 project()；路由插件自动添加 API/processor，生成路由表 | commands.txt；2 分 38 秒 |

架构提示：独立消费样例有自己的 settings，不纳入主工程属于预期；CoreUtils.init 多处文本匹配包含库说明/定义，App 只有一处实际初始化。

## 已修复的迁入问题

- 对齐 Kotlin/KSP、中央依赖目录与模块 project 路径，CoreBase 改用本地 XPopup，公开继承类型所需依赖以 API 暴露。
- Agora 4.7.0 在 Maven Central 不存在，使用已发布的 4.6.4，并完成代码编译。
- 路由插件依赖添加时机过晚导致 KSP 不生成；改为提前注册延迟依赖，独立消费样例直接引用生成类验收。
- NAS 去掉具体 ProBilling、宿主资源和固定启动页依赖；无启动页通知不再影响后台任务，权限策略由宿主回调提供。测试保持传输/恢复断言并适配新边界。
- 元数据测试夹具改用迁入后的路径；独立消费工程补齐 AndroidX 设置。前轮失败均已在最终通过运行中复测。

## 未执行与覆盖缺口

- `MetadataScrubberTest.testScrubMetadataKeepOrientation` 是上游原有 @Ignore：需要 Android/ExifInterface 环境；本次未新增跳过项。
- 无既有 JVM 测试：core_router_annotation, core_router_api, core_share, lib_agora, lib_auth_credentials, lib_auth_firebase, lib_auth_google, lib_beauty_mediapipe, lib_camera_capture, lib_live_api, lib_live_beauty, lib_tencent_beauty_kit, lib_upload。这些库仅通过构建与发布。
- 未执行真机、ARM native 加载、16KB page size、OEM/后台/SAF/权限/RTL/视觉、多设备浏览器大文件与真实 SMB/FTP/SFTP/WebDAV 交互验收。
- OAuth/Firebase/FCM/支付、Agora/腾讯美颜授权、模型下载与数据签名需要消费宿主配置和运行验收；不视为商用通过。
- Billing 只采用 NAS 核心基线；七份差异只记录未语义合并。原项目未回接，不能称为迁移闭环完成。
- 原照片业务数据库与 RoofQuote 业务模板策略留在原工程，对应业务测试未迁入通用库。
- Gradle 9 弃用提示及上游测试警告仍存在；当前固定 Gradle 8.13 构建通过。Robolectric 在 JDK 17 不覆盖 API 36，不把 JVM 结果当 targetSdk 36 真机验收。

## 基线与清理

11 个计划项写入首次验证基线，范围限本报告记录的构建、既有 JVM 测试与消费检查。摘要见 [summary.json](evidence/2026-10-23-import/summary.json)，命令与退出码摘要见 [commands.txt](evidence/2026-10-23-import/commands.txt)。完整临时日志留在被忽略的 .ai-work/migration；只提交可跟踪的精简证据。测试临时目录由现有测试清理；保留本地产物用于复核，设备和原工程未变更。
