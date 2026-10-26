# DevLib 当前测试计划：Demo 业务与接入优化

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
