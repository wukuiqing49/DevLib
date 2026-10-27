# 三入口信息架构重整（2026-10-27）

| ID | 范围与预期 | 验证方法 |
| --- | --- | --- |
| NAV-DIRECTORY | 10 个功能库含 Google/统一文档；基础与功能不重叠；8 个基础模块组织成 5 套体系；路由子模块可搜索 | DirectoryPolicyTest JVM |
| NAV-SHORTCUT | 常用优先、最近补充、去重并排除已移除及跨页路由 | DirectoryPolicyTest JVM |
| NAV-PAGES | 三个顶层独立子页面，查询/分类不因切页清空；工作区文件与历史分开 | App/AndroidTest APK 构建，Pixel 8 定向冒烟 |
| NAV-INTEGRATION | 独立接入详情、路由子模块切换、正确 runtime/KSP/plugin 指引 | 静态检查与 Pixel 8 页面抽查 |
| NAV-WORKSPACE | 独立文件/历史适配器、图片缩略图、最多 20 项选择与兼容工具流转 | 公共层 JVM、文档模型 JVM、静态检查；真实导入分享不执行 |
| NAV-CATALOG | 原 18 库路由与 5 组初始化器保留 | 最终 APK 目录门禁 |

复用 BaseVMFragment、BaseVMTitleActivity、BaseViewModel、BaseRecyclerViewAdapter、XPopup、ImageLoaderUtil；不改基础库实现。使用架构/UI/国际化/构建/功能测试 Skills。沿用用户本会话明确的 Pixel 8 部署授权，仅覆盖安装和本地导航冒烟，不进行购买、真实服务器访问、分享、文件删除或清数据。

API 26/37、平板、多窗口、全语言视觉、系统强杀恢复、Release/R8 与所有业务闭环未纳入本轮设备验收。不创建或调整 CI；完成验证后按用户明确要求提交并推送。以下计划为历史。

---

# Pixel 8 图标包部署（2026-10-27）

用户明确要求运行到 Pixel 8，本轮覆盖默认设备排除，仅对该设备覆盖安装最新 Debug APK、冷启动、首页截图及当前进程崩溃日志抽查。不清数据、不卸载、不操作账户和外部服务。图标已本地构建及资源检查；Launcher 实际裁切、单色主题图标及全功能回归不属于此次运行验收。

---

# 当前增量计划：统一记录导出（2026-10-27）

本轮将 PDF 与 Office 合并为 `core_document`，共 19 个模块、18 个能力入口。使用当前 Android 17 / API 37 工具链；下文早期工具链和模块数仅为历史记录。

| ID | 范围与预期 | 方法 |
| --- | --- | --- |
| RECORD-CONTENT | 同一记录的字段、备注、明细、附件进入三格式；数字与文本保持类型 | 库 JVM、Office XML 检查；PDF 设备用例仅编译 |
| RECORD-TEMPLATE | 区块排序/隐藏、列选择、样式、模板版本与水印交换；拒绝非法输入及路径 | RecordTemplateTest JVM |
| RECORD-PROFILES | 公司、个人及多人签署共享布局，快照保存原资料和图片 | JVM 快照与 Office 内容检查；真实图片设备用例仅编译 |
| RECORD-ATOMIC | 整批成功发布，失败/取消回滚，不覆盖历史文件 | RecordExporterTest JVM |
| RECORD-HOST | Base 页面、模板管理、资料编辑、图片选择与 PDF 首页预览 | document/common JVM、App 构建、严格资源/架构/源码检查 |
| RECORD-PUBLISH | 合并后 AAR 及新增模型依赖可由独立消费者接入 | 本地 Maven 发布、消费者 compileDebugKotlin |
| RECORD-CATALOG | 合并旧入口，保留图片 PDF 二级路由与 SPI | 最终 APK 目录校验 |

排除：adb/真机/模拟器、真实分享、Word/Excel/WPS/LibreOffice 人工打开与打印、API 26/37 运行期、日夜/大字体/旋转/进程死亡端到端、Release R8。设备用例构建不计为执行通过；不创建 CI，不推进未执行项基线。基础库源码保持不变。

---

# 历史测试计划：Demo 业务与接入优化

日期：2026-10-26，Asia/Singapore。基于 be55dc3 的增量工作区；19 个库模块不变，基础库源码不动。用户明确不做真机测试。

| ID | 变更与依赖 | 本轮验证及预期 |
| --- | --- | --- |
| DEMO-ERROR | 公共 DemoViewModel / DemoErrors，影响全部 Demo | 7 项 JVM：输入、权限、缺文件、超时、证书、包装网络异常、循环 cause；提示不引用底层 message |
| DEMO-BILLING-RESUME | Google Activity → PurchaseStateRecovery → 现有 BillingManager | 6 项 JVM：未配置不连接；错过回调后主动恢复；旧请求不覆盖新结果；重新配置清空旧结果；失败与超时可重试 |
| DEMO-TASK-HISTORY | Transfer 页面 → 数据库任务流与本地选择记录 | 5 项 JVM：恢复选择、隔离宿主其他任务、兼容旧本地/新远端连接、删除记录回退、相似前缀拒绝 |
| DEMO-PAIRING | Drop 页面单项审批及接收页编号展示 | Node 5 项；批准/拒绝、下载流程及编号展示/不展示 ticket；人工设备配对排除 |
| DEMO-UI-BUILD | Upload 协议选择与参数校验、Transfer 远端目标/任务列表、五种图片路径、格式化其他 Demo | App/AndroidTest APK 构建，资源/基础样式/注册关系和 UI 静态检查；不把构建称作视觉验收 |
| DEMO-CATALOG | lib_operations 产物事实纠正，格式化后的路由类识别 | Python 8 项策略测试；构建前 artifact 分类 15 AAR / 4 JVM；APK 19 模块、18 入口、5 组 SPI |
| DEMO-PUBLISH | 发布消费者扩展到全部 10 个功能库公开入口 | 本地发布全部库后编译独立消费工程；不通过 project() 或直接补第三方 SDK 依赖掩盖公开边界 |
| DEMO-CI | CI 授权边界与本地验证 | 撤回本次未经授权新增的工作流；检查无残留触发配置，本地验证入口保留 |

环境：Gradle 8.13、AGP 8.13.2、Kotlin 2.1.21、JDK 17、compile/target SDK 36、Demo min SDK 26、XML/ViewBinding。SDK 路径和机器信息不入库。

本轮测试重点是演示层与新增门禁，底层库实现未变，不重复执行其全部 JVM 用例。2026-10-23 的基础与功能库证据为历史通过未复测。本轮不创建远端 CI；全库 testLibraries 未在本轮执行。

排除：设备安装/adb 操作、深浅主题与字体缩放、旋转/进程死亡端到端恢复、Insets 与键盘实测、真实 OAuth/Firebase/Play 交易、SMB/WebDAV/SFTP/FTP 服务、局域网浏览器实传、Native 格式/ABI/16KB 设备验收、生产签名发布。

新增纯 JVM 的恢复与选择用例不能冒充 Android 系统进程重建测试。远程连接只在用户点击测试或确认任务后发起；密码输入不保存 View 状态。Transfer 底层加密保存密码供后台任务，Upload Demo 不落盘凭据。

证据留在 evidence/2026-10-26-demo-optimization，临时日志在 .ai-work。没有可靠源码工作区指纹，不推进 TEST_BASELINE.json。

## 开发者工作台增量（2026-10-26）

| ID | 实现与依赖 | 验证 |
| --- | --- | --- |
| WORKBENCH-SHELL | 四入口、分类搜索、收藏与最近使用 | App/AndroidTest APK 构建与目录门禁；视觉未执行 |
| WORKBENCH-ASSETS | 私有文件副本、ID 流转、产物关联与另存 | WorkspacePolicy JVM 7 项；SQLite SQL 结构检查；Android 数据库及 SAF 未执行 |
| WORKBENCH-TABLE | 单元格与行列编辑、SavedStateHandle | DocumentTable JVM 5 项；实际恢复未执行 |
| WORKBENCH-PAGES | PDF 排序缩略图、元数据字段、归档多产物 | App 构建；设备交互未执行 |
| WORKBENCH-CATALOG | 多行目录与带构造参数的 VM 识别 | 目录策略增至 10 项，保留 APK SPI/排除检查 |

本轮受公共宿主层影响，执行 common/document/files/google JVM；不重跑 core 全库及本地发布，因为未改底层库产物。保留此前本地发布和消费者编译为历史证据。工作台本轮证据使用 evidence/2026-10-26-workbench，不推进有效基线。

## Android 17 / API 37 配置回归

- A17-BUILD：AGP 9.1.1 / Gradle 9.3.1 / Kotlin 2.2.10 / KSP 2.2.10-2.0.2，App Debug 与 AndroidTest APK 构建、相关 JVM 测试。
- A17-SKILL：SDK 与工具链门禁测试、临时目录脚手架生成与不支持版本拒绝、Agent 结构、工作流静态检查。
- A17-LAN（设备未执行）：共享/上传/远端传输权限允许、拒绝、永久拒绝、撤销及 Activity 重建；公网和本地文件不依赖 LAN 权限；任务恢复使用原任务目标。
- A17-UI（设备未执行）：API 37 手机、sw>=600dp 平板/折叠屏、横竖屏、多窗口、键盘、Insets、字体缩放及状态恢复，另保留 API 26 回归。
- A17-RELEASE（未执行）：release/R8、native 16KB、已发布 AAR/JAR 消费者回归。
- 用户长期排除真机验证；本轮同样不运行模拟器或真实外部事务。构建与静态结果不推进设备功能基线。

## 注释与缺译收尾（2026-10-26）

- COMMENT-CLOSURE：按原审计的 241 项逐条补充职责、参数含义和异步/权限/文件边界；33 个 feature Kotlin 文件仅增加 KDoc。对本轮修改前快照比对非注释 token，避免覆盖既有工作区实现。
- I18N-CLOSURE：feature_app 的 values-en 与 values-zh-rCN 各补齐 81 项；核对默认资源的 key、占位符及换行一致性，执行全宿主严格缺译检查。
- SOURCE-ENUM：修正 Kotlin enum class 的名称解析，测试有/无中文 KDoc 的枚举声明，不增加规则豁免。
- 构建：App assembleDebug 同时验证所有被宿主使用的 feature；执行 common/document/files/google 已有 30 项 JVM 用例。未改 core 运行逻辑，不重复全库测试或发布。
- UI 静态复核：列表条目与按钮为 wrap_content、正文自然换行，保留 Base、MVVM 和唯一 Insets owner；没有改布局。英文长文案在小屏、横屏、大字体、RTL 的实际显示，以及真实外部事务仍按用户要求排除设备验证。
- 当前构建环境沿用上节 Android 17 工具链，不能将文首历史 SDK 36 环境当作本轮环境；测试结果以最新 TEST_REPORT 为准。

## 稳定性与业务英文覆盖（2026-10-26）

- STABLE-FINALIZE：历史持久化普通失败需要显示独立提示，始终解除 busy；正常完成、磁盘错误、错误提示回调异常、任务取消、取消叠加磁盘故障及显式取消由 6 项 JVM 用例覆盖。产物和原操作结果不因历史失败被替换。
- STABLE-SELECTION：最多 20 项、禁止重复，任何记录/文件缺失或类型不符时拒绝整批；清空旧输入，成功后才记录已接收状态。5 项 JVM 用例覆盖保序、缺失后重试、文件删除、类型混入、空/超量/重复。
- I18N-BUSINESS：六个 feature_demo 模块新增 values-en，检查所有字符串及 string-array；默认中文继续作为中文回退。目标档案强制宿主存在 en，避免整个语言目录缺失被漏检。协议枚举、模块名、用户输入和真实服务器返回不机械翻译。
- RECEIVER-I18N：接收网页按浏览器 zh 选择中文，其余使用英文；7 项 Node 用例覆盖原协议流程、英文错误与未知语言回退；仍不执行真实网络传输。
- STABLE-BUILD：Debug App、common/document/files/google JVM、App Release 签名门禁、本地库发布及不使用 project() 的消费者 compileDebugKotlin。正式签名缺失不得修改门禁或以 Debug 签名冒充正式构建。
- 范围排除：基础库源码、设备/视觉、真实购买和外部服务；R8、原生 ABI/16KB 的设备结论不能由库 Release 编译代替。测试基线不推进未执行项。


## 2026-10-27 NAS / USB 轻量库迁入

- NAS-UNIT：core_nas 原有协议验证、相对路径及 MockWebServer 用例，本地 JVM/Robolectric；不访问真实 NAS。
- USB-UNIT：core_usb 原有 SAF 提供方测试，使用本地 Robolectric 夹具；不操作真实盘。
- STORAGE-LAB：宿主隔离读写测试的文件归属、冲突、内容不一致、取消、清理错误、内存边界与下载超限清理。
- WORKSPACE-STORAGE：NAS/USB 路由、工作区输入选择及上传库退役协议提示，执行受影响宿主与 lib_upload 单元测试。
- STORAGE-INTEGRATION：20 库本地发布、仅 Maven 坐标消费者编译、App Debug 构建及 APK 19 页面/路由校验。
- STATIC-STORAGE：Agent 结构、九个宿主模块严格 i18n/UI/架构检查与变更源码中文注释审计。
- 排除：按用户要求不执行 adb、真机、模拟器、外部服务器或真实 USB 测试。视觉、多窗口、字体放大、授权恢复、拔盘及 Android 17 权限运行期检查未执行。
- 旧 lib_transfer 的持久队列、同步、历史数据库不迁移；不运行已移除库测试。测试不能代表后台传输或所有 NAS 固件通过。


## 2026-10-27 Google 评分功能适配

- GOOGLE-RATE-POLICY：使用时长、关键操作门槛、30 天冷却、时间回拨、零时间戳、关闭偏好及配置合法性，执行纯 JVM 测试。
- GOOGLE-RATE-FLOW：本地 Robolectric + 官方 FakeReviewManager，检查事件去重、有界存储、失败节流、无资格不创建客户端、前后台切换、请求前取消和流程中取消防重入。
- GOOGLE-RATE-HOST：复用现有 Base/ViewModel/Utils，增加评分条件诊断、完成事件模拟、开关、商店与独立反馈入口；回归 Google 宿主现有购买恢复测试。
- GOOGLE-RATE-PUBLISH：发布受影响 core_google，独立 Maven 消费者编译评分状态公开类型；App Debug、APK 路由和新增 Review SDK 类型检查。
- 原 SiteReport / PressIPTC 仓库只读，保留来源摘要；不迁入报告 ID 门槛、五星预筛选、品牌或邮件地址。
- 按用户要求不执行 adb、真机或模拟器；不提交真实评分或发送真实邮件。字体缩放、系统栏、Play 实际展示及分发资格保留设备验收项。


## 2026-10-27 全库可靠性优化

- LIB-RELIABILITY：testLibraries，执行所有正式库已配置的本地 JVM/编译测试；新增订阅账号/永久权益/未知状态、HTTP 非重放/实际取消、USB 失败清理、NAS 中断、分享关闭、解压预算、中间 Bitmap 预算、PDF 溢出、Office 替换/中断、EXIF 事务与操作契约用例。
- HOST-RELIABILITY：common/files/document/google 的 JVM 测试，包含错误分类和现有流程；App Debug 构建、19 页面 APK 目录检查。
- PUBLISH-RELIABILITY：20 个库本地发布，独立 Maven 消费者 compileDebugKotlin，不用 project 依赖掩盖公开 API/传递依赖问题。
- STATIC-RELIABILITY：Agent 结构、九个宿主严格 UI/i18n/build/architecture；宿主全文源码审计和功能库修改声明人工复核；基础库源码差异核对。
- 回环 HTTP、Robolectric SAF、FakeReviewManager 和图片测试不代表真实服务器、USB 设备、购买或 UI 展示通过；按用户要求不执行 adb、真机、模拟器。
- App 正式签名/R8、native ABI/16KB、阅读器视觉和 Android 17 运行期行为未验证；不推进这些用例基线。


## 2026-10-27 Pixel 8 定向运行

用户明确要求运行到 px8，本任务覆盖历史设备排除偏好。PX8-DEPLOY：Debug 构建、覆盖安装、冷启动；PX8-PDF-SMOKE：首页进入 PDF，内置示例图片生成并预览。仅操作 Pixel 8，不清数据，不做登录购买、真实 NAS/USB 或外部分享；Android 16 结果不能代表 Android 17 验收。


## 2026-10-27 UI 与色彩统一

- UI-THEME：语义色深浅主题、主次按钮、输入容器和结果卡片，严格资源/i18n/构建检查，12 组文本对比度。
- UI-SOURCE：任务前快照对比，42 个 app/feature Kotlin/XML 文件审计，人工复核 Base 复用、中文 KDoc 与 Insets owner。
- UI-BUILD：App Debug、公共层现有 27 个 JVM 用例、APK 目录路由校验；仅资源主题 bool 收尾后重跑 App 构建。
- UI-PX8：延续用户明确真机授权，仅 Pixel 8；首页深浅、横屏工具列表、130% 字体、PDF 键盘与示例生成/预览、Google/NAS 表单。无真实交易/服务器/USB/分享。
- 完成后恢复主题/字体/旋转偏好。API 37、平板、多窗口、三键导航、200% 字体、RTL/英文视觉、全 19 个业务闭环及 Release 不纳入已验收范围。
