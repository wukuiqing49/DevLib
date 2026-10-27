# 2026-10-27 页面、配色与窗口适配优化

按项目 UI、架构、国际化、构建及功能测试 Skills，统一 19 个能力入口共享的宿主视觉体系。蓝色主操作、青色辅助信息、中性背景与独立结果卡片，明确主次/停止操作；43 处表单容器显式共享样式。修复深色返回图标、标题单位重复缩放风险、无产物时空操作区、横屏固定页头挤没列表及 IME 避让。基础库源码保持不变。

- 构建：最终 Debug 成功；公共层 JVM 27/27 通过；资源初始系统栏 bool 的最后补充仅重跑 App 构建，未重跑无关库测试。
- 静态：9 个宿主模块 UI/严格 i18n/构建/架构通过；42 个任务文件源码审计 0 项；12 组文本颜色对比度 >=4.5。保留 2 项既有架构提示，不是设备验收。
- APK：20 模块 / 19 页面 / 5 路由组通过。
- 真机：用户上一轮明确授权 Pixel 8，本轮延续页面复核；Android 16 / API 36。覆盖安装及冷启动通过，深浅主题首页、横屏工具列表、130% 字体首页、PDF 输入键盘、示例生成/结果预览、Google/NAS 长表单抽查通过；当前进程日志无崩溃关键字。最终主题初始图标 bool 补充后重新安装并复核深浅首页，详细阶段见 commands.md。
- 手机偏好恢复：深色 yes、font_scale=1.0、自动旋转=1、user_rotation=0；保留 App 内测试示例和 PDF 产物，无清数据、购买、真实存储访问或外部分享。
- 未验证：API 26/37、平板/多窗口/三键导航、200% 字体、RTL/英文视觉、全部 19 项业务闭环、真实第三方服务及 Release/R8。

证据：[汇总与阶段边界](evidence/2026-10-27-ui-refresh/summary.json)、[命令](evidence/2026-10-27-ui-refresh/commands.md)、[截图预览](evidence/2026-10-27-ui-refresh/overview.png)。[页面分析与实施说明](../ui-page-review.md)。源指纹与 APK SHA 可追踪；不推进任何历史全库或设备全功能基线。未提交、未推送、未修改 CI。

# 2026-10-27 Pixel 8 真机运行

用户明确授权本次设备操作；Pixel 8 实际 Android 16 / API 36。Debug 构建、覆盖安装、MainActivity 冷启动与内置示例图片生成 PDF / 首页预览通过；当前进程日志未匹配到崩溃关键字。返回箭头深色对比度偏低，留作 UI 优化项。未执行全库设备验收或 Android 17 运行期验证。

证据：[Pixel 8 验证记录](evidence/2026-10-27-px8-smoke/result.md)。不推进全功能基线。

# 最新测试报告：全库可靠性优化

日期：2026-10-27。范围为当前全部正式库的本地回归、功能引擎可靠性修复、工作台错误反馈与独立发布接入。源码仍在未提交工作区；[证据摘要](evidence/2026-10-27-library-optimization/summary.json) 保留 HEAD、Git 可见源码指纹、APK 摘要与命令。[逐库修改与边界](../library-optimization.md)。

## 修改结果

- 基础 8 模块源码保持原样，仅修正部分路由接入文档；原项目仓库未写入。20 模块仍为 8 基础、11 功能、1 内部支撑，16 AAR、4 JVM JAR，19 页面。
- Google 订阅查询与永久权益分离，校验当前账号及查询中的切换；未知或部分失败不能确认订阅时明确抛错，宿主保留旧状态。到期时间 0 表示未知，不冒充服务端时间。
- HTTP 写请求禁用自动重放与重定向；协程取消实际 Call，关闭响应体并校验 URI 源长度。FTP/FTPS、NAS 复制保留中断和取消；USB 失败尽力删除本次创建 URI，清理错误附加原异常。
- 分享结束时某个流关闭失败仍完成其他连接、监听及线程池清理；浏览器夹具改为工作台当前接收页，不恢复 lib_html。
- 解压补输入预算、严格路径与重复目标防护；图片解码检查实际中间像素预算并补失败释放；二维码补识别上限、生成清理及扫描防重；PDF 防布局参数溢出。
- Office 在同目录原子替换旧导出，分块响应中断并校验 XLSX 容量；EXIF/XMP 组合写入在副本成功后才发布。元数据依赖、模板数据库、服务与 native 的接入路径已明确，未声称物理拆包或按需分发。
- 工作台继续复用 Base、XML/ViewBinding/MVVM、既有组件与唯一 Insets owner。新增预算、冲突、格式、取消、文件 IO 的资源化错误提示，中英文齐全，不直接展示底层凭据相关消息。

## 本地验证

- `testLibraries`、common/files/document/google JVM 及 App Debug 构建成功。最后分享关闭测试变更后单独回归 lib_drop 并重建 App。
- 当前 Gradle 验证结果共 **361 项：360 通过、0 失败/错误、1 原有跳过**；新增回归 30 项。输入未变化的任务复用 Gradle UP-TO-DATE 结果，不宣称 361 项全部重新执行。详见 [逐模块结果](evidence/2026-10-27-library-optimization/module-results.json) 和 [JUnit](evidence/2026-10-27-library-optimization/junit-results.xml)。
- 原有跳过为 `MetadataScrubberTest.testScrubMetadataKeepOrientation`，源码保留 @Ignore，原因是普通 JVM Android Pair 环境限制。本次没有关闭检查，也没有推进该集成用例基线。
- 20 库本地 Maven 发布成功；独立消费工程 `compileDebugKotlin` 成功，路由插件和全部功能库通过发布依赖接入。
- 最终 APK 校验：20 模块、19 页面、5 路由初始化器；未恢复旧传输引擎。目录 Python 测试 10 项、接收页 Node 测试 7 项通过。
- Agent 结构、九个宿主模块严格聚合校验成功：build/UI/i18n 零警告；architecture 保留 2 项原有启发式警告（独立消费者未注册主 settings、CoreUtils.init 文本命中）。
- 宿主全文审计 32 文件、11 个新增/重写文件定向审计均为 0 发现；功能库已有文件只人工复核本次修改声明的中文文档，不冒充所有上游历史代码已全量合规。Base 复用、资源化和适配静态复核完成，视觉不在此结论内。
- Git 差异检查通过；基础库源码无差异，未创建或调整 CI，未提交或推送。首次 Windows 编译 JAR 占用通过停止 Gradle daemon 重跑解决；测试夹具修正未改变生产依赖版本。

## 未验证范围

按用户要求未执行 adb、真机或模拟器。真实 NAS/FTP/FTPS、USB 拔插/撤权、Google 登录/购买/评分、Android 17 权限与系统栏运行表现、日夜主题/字体/多窗口视觉、实际 Office/PDF 阅读器渲染、App 正式签名与 Release R8、native ABI/16KB 设备验收均未执行。回环服务与 Robolectric 不代表这些场景通过。

USB 清理、NAS/上传远端提交核对及 URI/PFD 回滚存在宿主责任；不能从构建通过推断跨协议原子提交、可靠后台恢复或所有格式无损。本次仅推进上述本地可运行用例基线，其余历史基线保持。

[命令](evidence/2026-10-27-library-optimization/commands.txt) · [静态结果](evidence/2026-10-27-library-optimization/static-checks.txt) · [源码指纹输入](evidence/2026-10-27-library-optimization/source-files.json)。临时完整日志位于 `.ai-work/library-optimization-*.log`。

---

# 历史测试报告：Google 评分迁入

日期：2026-10-27。范围：SiteReport / PressIPTC 评分能力适配至 core_google。未提交工作区，来源提交与 SHA-256、源码工作区指纹和 APK 摘要见 [本轮证据](evidence/2026-10-27-google-rate/summary.json)。

- 两个来源均有评分功能，采用 SiteReport 默认 1 天使用时长、1 次关键操作和 30 天请求间隔，抽成可配置通用策略；保留 com.wkq.google.rate 包名及 GoogleRateManager 门面。
- 去掉报告 ID、GoogleKit 配置、品牌与邮箱耦合，替换为宿主关键完成事件、显式关闭偏好及 Play 原生请求。没有迁入高低星分流弹窗或 isRated/markRated 误判。
- 请求前持久保存尝试次数和冷却；SDK 异步返回后检查 RESUMED 生命周期。协程取消原样传播；已打开流程由 Task 实际结束释放防重入，不因宿主取消而重复请求。
- 工作台在既有 Base/XML/ViewBinding/MVVM 页面增加评分策略检查、模拟完成事件、关闭/恢复提示、商店评分及独立邮件反馈；手动跳转复用 core_utils.IntentUtil。默认策略与 Play 配额不强制绕过。
- JVM 共 50 项通过：新增评分策略 9、官方 FakeReviewManager/Robolectric 流程与存储 10、既有计费 25、宿主购买恢复 6；失败/错误/跳过均为 0。目录校验脚本 10 项通过。
- App Debug 最终构建、core_google 发布与独立 Maven 消费者编译通过；消费者通过公开 GoogleRateState 签名编译，无需直接声明 Play Review SDK。
- 最终 APK 校验仍为 20 模块、19 页面、5 个路由初始化器；GoogleRateManager、GoogleRatePolicy、ReviewManagerFactory 均存在，旧传输引擎保持移出。
- Agent 结构、九个宿主模块聚合严格国际化检查通过；Google 宿主在最终资源变更后单独严格 en 检查通过。build/UI/i18n 零警告，架构保留两项原有启发式警告；本轮 4 个 Kotlin 文件中文注释/Base/资源化审计零发现。
- 初次构建遇到 Windows 编译 JAR 文件占用，停止 Gradle 守护进程后重跑成功；既有 Gradle/API 弃用、SDK XML 与 Debug native 符号警告不作为新功能已设备验收的依据。
- 两个来源仓库及基础库未修改；先前 NAS/USB 和 Skill 工作区改动保留。仅更新本次实测评分用例基线，未创建/修改 CI，未提交或推送。

未执行：按用户要求不执行 adb、真机或模拟器；未提交真实评分、未发送邮件。FakeReviewManager 不显示评分卡片，不能证明 Play 分发资格、配额或真实 UI 展示。Android 17 运行、日夜主题、系统栏、大字体/多窗口及商店/邮件实际跳转仍待设备验收；App 正式签名与 Release R8 未执行。流程结束不代表评分已提交。

[接入说明](../../core/core_google/README.md) · [来源记录](evidence/2026-10-27-google-rate/provenance.json) · [JVM 明细](evidence/2026-10-27-google-rate/junit-results.xml) · [静态检查](evidence/2026-10-27-google-rate/static-checks.txt)。临时日志为 .ai-work/google-rate-*.log。

# 历史报告：NAS / USB 迁入

日期：2026-10-27。范围：NAS / USB 轻量库迁入、独立工作台页面与 lib_transfer 退役。当前为未提交工作区；所用源文件指纹、命令、APK 摘要及测试明细见 [本轮证据](evidence/2026-10-27-nas-usb/summary.json)。

- 新增 core_nas、core_usb，保留公开包名；18 个源码及测试文件与源仓库快照逐字节一致。原仓库未写入，基础库源码未改。
- 两个页面复用 Base 页面、ViewModel、列表及确认组件；中文与英文资源完整，Base 接管 Insets。支持连接或授权、浏览、8 MiB 有界下载、独占上传和 4 KiB 读写校验清理。
- JVM 53 项通过，失败/跳过均为 0：NAS 8、USB 10、隔离读写宿主 7、工作区 25、上传库 3。目录策略 Python 10 项通过。
- App Debug 构建通过，20 个库本地发布通过，独立 Maven 消费工程编译通过。APK 校验确认 20 模块、19 页面、5 路由初始化器，新 NAS/USB 公开类存在，旧传输引擎类不存在。
- Agent 结构、九个宿主模块严格国际化检查通过；build/UI/i18n 零警告，架构保留两项原有启发式警告。宿主变更源码审计 22 文件、0 发现；这不代表上游源码所有既有声明已补中文注释。
- 构建中修正重复发布声明、保留 Google 测试使用的协程别名；Windows 文件占用在停止守护进程并重跑后消除。现有 Gradle/API 弃用、SDK 工具 XML 及 Debug native 符号裁剪警告未当作功能失败，也未据此改动基础库。
- 仅推进本次实测用例基线；其他历史用例保持原基线。此前 Skill 优化工作区保留，本次未创建或修改 GitHub Actions，未提交或推送。

未验证：按用户要求不执行 adb、真机或模拟器。真实 NAS 固件、USB 拔插、SAF 提供方差异、授权恢复、Android 17 局域网权限运行、日夜主题/大字体/多窗口视觉验收未执行；App 正式签名、Release R8 和 native 16KB 设备验收未执行。本次未迁入后台队列或旧任务数据库，不宣称可后台恢复。

[接入和页面操作](../nas-usb-migration.md) · [JVM 明细](evidence/2026-10-27-nas-usb/junit-results.xml) · [静态检查](evidence/2026-10-27-nas-usb/static-checks.txt) · [来源核对](evidence/2026-10-27-nas-usb/provenance.json)。临时构建日志保留于 .ai-work/nas-usb-*.log。

# 历史报告：2026-10-26：稳定性与业务国际化收尾

日期：2026-10-26。证据见 [本轮验证摘要](evidence/2026-10-26-stabilization/summary.json)。

- 修复操作记录保存失败后忙碌状态不能可靠复位的问题，保留当前结果并提示记录失败；取消语义保持。
- 工作区输入按整批校验，拒绝缺失、重复及不支持的文件，失败后允许重试。
- 六个功能演示模块补齐 24 个英文资源文件（362 个字符串、7 个数组）；浏览器接收页支持中英文。语言门禁增加必需目录和数组校验。
- Debug 构建通过；41 项宿主 JVM、40 项 Python、7 项浏览器脚本测试通过。源码审计 97 个文件、0 待复核项；严格国际化检查通过。
- 19 个库本地发布成功，独立消费工程编译通过。目录校验确认 19 个模块、18 个独立页面，lib_operations 为内部支撑。
- App Release 因缺少完整正式签名配置被现有门禁拦截，App R8 与签名打包未通过验收；未降低签名要求。
- 基础库源码及 GitHub Actions 未改动。按用户要求未做真机测试；实际购买、远程服务、屏幕适配与 native 16KB 设备表现未验收。

验证命令及日志结论已提取至摘要；本地临时日志不纳入仓库。提交及推送状态以 Git 历史为准。

# 上一轮：中文注释与缺译收尾

日期：2026-10-26。范围：原审计 241 项中文注释、feature_app 英文与简体中文各 81 个显式翻译键。

## 本次结果

- **241 项注释完成**：33 个宿主 Kotlin 文件补充类型、方法、私有方法、override 与匿名回调的中文 KDoc，说明实际职责和关键边界。按本轮修改前快照对比，非注释 token 完全一致；基础库源码未改。
- **162 条语言资源完成**：新增 values-en、values-zh-rCN 下各自的 catalog.xml、workbench.xml。每种语言 81 项；key、占位符与换行一致，英文无中文回填。
- 源码审计：当前工作区 71 个主源码/XML 文件，0 发现、0 例外。修复 enum class 把类型名识别为 class 的问题，并增加有/无中文文档的回归测试；该枚举确实缺文档，仍已补齐。
- 聚合静态检查：9 个宿主模块通过；严格 i18n 0 警告、UI 和构建配置 0 警告。架构仍有 2 项原有启发式警告（独立消费者未注册主工程、CoreUtils.init 多处文本匹配）。
- Python 测试 37 项通过：公共脚本 32 项、i18n 5 项。i18n 测试中的畸形 XML 错误输出属于预期失败夹具，不是项目资源错误。
- **App Debug 构建通过**（1 分 40 秒）；common/document/files/google 的 **30 项 JVM 测试实际执行通过**，0 失败、0 跳过。
- 构建保留既有 Kotlin StringRes 注解目标提示与 Gradle 弃用提示，本轮未改变注解或构建行为。

## 证据与命令

[变更及资源核对](evidence/2026-10-26-comments-i18n/changes.json) · [源码审计](evidence/2026-10-26-comments-i18n/source-audit.json) · [构建与测试摘要](evidence/2026-10-26-comments-i18n/summary.json)。临时日志位于 .ai-work/docs-i18n-build.log、.ai-work/docs-i18n-static.log。

```powershell
python -B .agents/scripts/validate_android_source.py --project-root . --report .ai-work/source-audit-after.json
python -B -m unittest discover -s .agents/scripts/tests
python -B .agents/skills/android-i18n-workflow/scripts/test_validate_i18n_resources.py
python -B .agents/scripts/validate_android_workflows.py --project-root . --skip-figma --strict-i18n
.\gradlew.bat :app:assembleDebug :feature:feature_demo_common:testDebugUnitTest :feature:feature_demo_document:testDebugUnitTest :feature:feature_demo_files:testDebugUnitTest :feature:feature_demo_google:testDebugUnitTest --no-daemon "-Pkotlin.compiler.execution.strategy=in-process" --console=plain
git diff --check
```

## 未验证范围

源码工具是有限静态检查，零发现不代表全部代码语义与翻译质量获得自动证明。布局静态复核保留自然换行、wrap_content 和现有 Base/MVVM/Insets；未做小屏、横屏、大字体、RTL、深浅主题视觉实测。按用户要求未运行设备命令、OAuth/购买/远程服务等真实外部事务。未执行 release/R8、全 core 测试或发布消费者回归；本轮未改这些实现。不推进设备或发布有效基线。

未新增 CI、未提交、未推送。下方为历史记录，原 241 项与 81 项数字是优化前审计状态，不再代表当前欠账。

---

# 上一轮：Agent 与 Skills 整体优化

日期：2026-10-26。分析及变更详见 [整体审查](../agent-skill-review.md)。

- 公共脚本 31 项、生成器 8 项、Figma 4 项测试通过，共 43 项。
- Agent 结构和 9 个宿主模块聚合静态校验通过；保留 2 项架构警告、2 项 i18n 缺译警告。
- 源码审计覆盖当前工作区 67 个选中文件全文：241 项 DOC001 待复核，退出码 1，未作为通过项。详见 [审计证据](evidence/2026-10-26-skill-optimization/source-audit.json)。
- feature_app 英文、简体中文目录各缺 81 个显式翻译键，仍需后续补译。
- 未运行 Gradle、设备或视觉测试；未改基础库源码和 CI，未提交或推送。下方保留历史验证，不能当作本轮重测。

---

# 上一轮：功能开发 Skill 约束

日期：2026-10-26。本轮只修改 Agent 规范、Skills、聚合校验脚本及其测试；没有修改 Android 业务或基础库源码。

- 强制功能开发读取 Base/UI/国际化/屏幕及系统适配规则；新增及修改的类、接口、具名对象、方法/函数需中文 KDoc/JavaDoc，含 private/override。类名、方法名保持英文及现有公开 API。
- 模块发现测试 3 项通过，覆盖启用/未启用模块、Kotlin 多行 settings、资源参数和自定义 feature 根目录。
- 聚合检查覆盖 9 个模块：app、feature_app、feature_res，以及 demo_common/foundation/files/image/document/google。
- Agent 结构、构建、UI、国际化校验命令均成功。构建/UI 零警告；架构保留 2 项现有启发式警告；国际化新增发现 2 项警告：feature_app 的 values-en、values-zh-rCN 各缺 81 个显式 key，目前使用默认资源 fallback，未补译。不是全语言验收通过。
- 中文注释和 Base/业务职责需改动级代码审查；本轮没有给所有既有源码补注释，也没有实现完整 Kotlin/Java 注释语义校验器，不能声称历史代码全部合规。
- 未运行 Android 构建/设备/视觉测试：本轮没有 Android 源码变化，沿用下方上一轮构建事实，不把历史结果算本次重测。未提交、未推送、未改 CI。

本轮命令：`python -B .agents/scripts/tests/test_validate_android_workflows.py -v`、`python -B .agents/scripts/tests/test_validate_agent_structure.py -v`、`python -B .agents/scripts/validate_android_workflows.py --project-root . --skip-figma`、`git diff --check`。本机日志：`.ai-work/skill-requirements-static.log`、`.ai-work/skill-requirements-tests.log`。

---

# 上一轮：Android 17 配置验证

日期：2026-10-26。范围：Android 17 / API 37 的项目配置、Skill、生成器与宿主局域网权限适配。

## 结果

| 检查 | 本次结果 |
| --- | --- |
| App Debug、Debug AndroidTest APK 构建 | 通过；AndroidTest APK 仅构建，没有安装或运行 |
| 独立库 JVM：testLibraries | 501 项：500 通过、1 原有跳过、0 失败、0 错误 |
| App 侧 JVM（common/document/files/google） | 30 项通过，无失败或跳过 |
| API 37 门禁测试 | 7 项通过 |
| 临时目录脚手架测试 | 8 项通过 |
| Agent 结构校验器测试 | 14 项通过 |
| 演示目录校验器测试 | 10 项通过 |
| 工作流聚合静态检查 | 通过；构建/UI/i18n 零警告，架构有 2 项现有启发式警告 |
| APK 页面、路由及 SPI 检查 | 19 模块、18 页面、5 路由初始化器，通过 |
| 最终 APK SDK / 权限 | aapt 核验 compile 37、target 37、min 26、ACCESS_LOCAL_NETWORK |
| Git 差异检查 | 通过；基础库源码无改动，CI 无改动 |

独立库跳过项为 `MetadataScrubberTest.testScrubMetadataKeepOrientation`，原源码有 `@Ignore`，说明本地 JVM 缺少所需 Android 运行行为。本轮没有新增跳过或关闭检查。

架构警告：独立 published-consumer 工程不在主 settings 注册；CoreUtils.init 文本扫描在工具库内命中多个位置。没有为消除启发式警告修改基础库。

AGP 9.x 的 builtInKotlin/newDsl 过渡配置与现有 Gradle DSL 有弃用警告，当前构建通过；AGP/Gradle 10 尚未兼容。没有以降低 SDK、屏蔽 unsupported SDK 警告或伪造 SDK 元数据通过构建。

## 执行命令

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :feature:feature_demo_common:testDebugUnitTest :feature:feature_demo_document:testDebugUnitTest :feature:feature_demo_files:testDebugUnitTest :feature:feature_demo_google:testDebugUnitTest --no-daemon "-Pkotlin.compiler.execution.strategy=in-process" --console=plain
.\gradlew.bat testLibraries --no-daemon "-Pkotlin.compiler.execution.strategy=in-process" --console=plain
python -B .agents/skills/android-build-workflow/scripts/test_android17.py -v
python -B .agents/skills/android-project-scaffold-workflow/scripts/test_render_project_scaffold.py -v
python -B .agents/scripts/tests/test_validate_agent_structure.py -v
python -B scripts/test_verify_demo_catalog.py -v
python -B .agents/scripts/validate_android_workflows.py --project-root . --skip-figma
python -B scripts/verify_demo_catalog.py --apk app/build/outputs/apk/debug/app-debug.apk
git diff --check
```

最终 App 构建：BUILD SUCCESSFUL，3m53s，573 tasks（134 executed / 439 up-to-date）。独立库测试：BUILD SUCCESSFUL，3m17s，267 tasks（95 executed / 172 up-to-date）。实际测试任务均已执行；NO-SOURCE 的注解/API 模块不计入测试通过数量。

本机完整日志在 `.ai-work/android17-final.log`、`.ai-work/android17-library-tests.log` 和 `android17-*.log`；不作为跨机器依赖。脱敏证据：[summary.json](evidence/2026-10-26-android17/summary.json)、[JUnit 统计](evidence/2026-10-26-android17/junit-summary.json)。先前开发者工作台结果保存在 `evidence/2026-10-26-workbench/`，不冒充本轮设备测试。

## 未验证与基线

按用户要求未执行真机测试；本轮也未执行模拟器、视觉、大屏/多窗口、权限实际交互与撤销、API 26 运行回归、真实登录购买或生产服务事务。没有执行 release/R8、native 16KB 运行及重新发布消费者测试。

构建与 JVM 测试通过不能证明上述运行能力已通过。TEST_BASELINE 不推进，因本轮源码尚未提交且没有建立可靠工作区指纹。未提交、未推送、没有创建或调整 CI。

配置与实现说明：[Android 17 兼容说明](../android17-compatibility.md)。
