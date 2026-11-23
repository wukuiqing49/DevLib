# core_log_report

用户主动开启的本地日志收集与分享模块。不依赖宿主 app、feature、账号或相机模块；管理页面复用共享 core_base 的 BaseActivity、BaseViewModel 与列表封装，minSdk 26。

Application 调用 LogReport.install(application, supportEmail)。邮箱可空，此时在外部邮件客户端手动填写。入口调用 LogReport.show(activity)。宿主通过 event(kind, code) 提交稳定标识和状态，通过 exception(operation, throwable) 记录脱敏栈。ALog 可通过 reportSink 桥接。

自动观察 Activity、Fragment 页面生命周期和触屏点击资源名。标签切换需要补 page/navigation 事件；无障碍触发和自定义画布操作仍由业务主动记录。输入内容不采集。通过本进程 logcat 尽力取得既有日志，失败或中断记 capture_gap。只处理当前进程。Android 12及以上对系统提供的原生tombstone只提取崩溃线程的模块、相对PC、ELF Build ID和函数，原始protobuf不落盘；配套离线工具按精确符号定位。系统不提供时明确标记缺失。系统提供ANR文本栈时，导出最多128KiB读取、256帧的脱敏摘要。PX8本轮系统未提供可读ANR栈，读取实际成功尚未验收。

采集模式显示为应用内独立可拖动悬浮胶囊，跨Fragment/Activity保持侧边和相对高度，不挤压或修改页面布局。默认位于侧边上部，点击打开日志上报管理页，拖动后吸附侧边；后台、停止与窗口销毁时移除。浅深主题有独立语义资源，尺寸随窗口、系统栏/刘海及键盘约束。FragmentActivity 可通过系统文档选择器另存，其他 Activity 支持分享。用户结束后从历史记录打开详情与附件摘要，ZIP 含环境、事件、掉记录计数、可用的崩溃栈和系统退出摘要。常见路径/凭据/邮箱经过替换，但第三方任意文本仍须在分享前检查。

记录约 4MiB 滚动窗口（当前与上一段），每段 2MiB，单条日志最多 2000 字符，队列 512。目录达 64MiB 时拒绝新会话；历史原始记录保留至用户手动删除，临时导出 ZIP 缓存超过七天可清理。关键崩溃记录独立写入。崩溃前尚未写盘的队列可能丢失，磁盘不可写时不保证保存。

没有注册前台服务。应用退到后台时遵循系统进程生命周期，系统退出后下次启动封存上次会话。用户可在历史中导出或删除。


## 可读报告与关联操作

每个ZIP现在包含summary.txt作为默认预览。environment.json的formatVersion为3，capture.json的exact只表示采集队列计数是否在完整排空后提交，不代表整机日志或所有业务路径都已采到。崩溃后的检查点为最后已知值。原始JSONL保留，序号和单调时间在调用时生成。旧报告可导出摘要，缺字段明确标记unknown。

install可传buildId和cameras白名单快照提供器。LensDock本地构建默认使用UTC生成时间，可通过-PlogReportBuildId指定发布构建标识；应随APK归档。相机只接收CameraLogInfo(model, firmware, protocol)。

LogReport.operation(name)返回可跨协程传递的ReportOperation。stage记录真实步骤和可选数量；error保存脱敏原因链；finish使用SUCCEEDED、SKIPPED、CANCELLED、FAILED或UNKNOWN，reason为源码标识。终态只提交一次。新采集开启后，旧操作的晚到回调被丢弃。请在异步任务实际结束处收尾，结果未知时保留UNKNOWN。已停收但仍未结束的操作在摘要中标记UNRECORDED。

Java栈最多4层原因、每层48帧，不包含异常消息。Logcat单条文本仍以2000字符为限。新的设备用例验证会话隔离、实际ZIP与摘要；没有验证全部项目的全部业务路径。


## 写盘、整理重试与卡顿

事件队列为单线程有界512条，写盘不占用采集状态锁。满队列立即返回并累计dropped；控制动作由独立线程排入同一写盘队列，结束先停收、再排空。开始准备与结束整理期间有明确提示，按钮禁止重复发起。整理失败保留active及原事件，显示重试；重新启动则作为中断记录保留。ZIP生成失败也不删除历史报告。崩溃采用独立线程写crash.txt与crash-capture.json，最多等待300ms，磁盘故障仍可能无法保存。

前台且正在采集时，每500ms发心跳，主线程未回应时约3/5/10秒各记录一次最多48帧主栈，恢复后写同stallId的持续时间。独立于普通事件队列保存最多4份main-stall-*.txt，包含主线程及其他线程状态/方法位置、堆内存、进程CPU累计时间和最近32条稳定操作；不输出任意线程名、消息或锁对象。系统未提供ANR文本时也能分析这些主动现场，不能据此识别锁持有者或直接判定系统ANR。只称应用观测卡顿，不能据此判定系统ANR。后台与采集结束停止采样。

凭据脱敏覆盖JSON键值、Authorization/Bearer、Cookie、带空格或引号的password/token、邮箱、IPv4/MAC和常见路径。先限制输入16KiB再处理、最终2000字符。无法解析的转义结构中出现凭据键会整体省略。仍不承诺理解第三方任意格式文本；禁止主动记录密码、照片、个人元数据。

## 原生符号归档与离线分析

每次需要回溯原生崩溃的构建后，将APK、原生符号目录和symbols.json一起保存。脚本只读实际APK并匹配ABI/模块/ELF Build ID，不把相同文件名当成相同版本。符号库不放入用户附件、不自动上传，第三方或系统库缺符号时保留UNRESOLVED。

```powershell
python core/functional/core_log_report/scripts/native_symbols.py archive --apk <APK> --ndk <NDK目录> --search-root core/core_gphoto2/build/intermediates/cxx/Debug --search-root core/core_image_render/build/intermediates/cxx/Debug --build-id <构建标识> --output <符号归档目录>
python core/functional/core_log_report/scripts/native_symbols.py symbolize --report <用户报告.zip> --symbols <符号归档目录>/symbols.json --ndk <NDK目录> --output <定位结果.txt>
```

不同构建类型要使用对应的cxx目录；脚本验证符号文件摘要及ELF Build ID，错版、损坏、缺失均不猜测源码。release若需要源码行号，应在正式构建中保留匹配的未剥离调试符号并运行相同归档；本轮只验证debug真实ELF，未宣称release已验收。Python需3.9及以上。

系统接口依据：[ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo)；白名单字段依据：[AOSP tombstone.proto](https://android.googlesource.com/platform/system/core/+/refs/heads/main/debuggerd/proto/tombstone.proto)。


## 系统未给ANR文件与系统库无源码时

用户仍按原入口收集和分享，无需连接电脑：卡顿现场在主线程恢复前由看门狗独立原子落盘；崩溃/强杀后下一次启动会保留已经完成的现场。结束时IO封存屏障等待正在写入的一份现场并禁止迟到写入。现场是最多24个Java线程、每线程32帧的有界快照，反映应用观测而非内核调度轨迹；VM整体暂停、极低内存或不可写磁盘仍可能阻止取样。

原生报告新增系统构建指纹、安全补丁版本、ABI及系统回溯提供的函数偏移。即使没有源码符号，也保留函数+偏移、模块PC与Build ID，可用于同版本问题聚类和继续核对调用链。不能把某个系统函数出现在栈上直接当作系统Bug根因。

开发者手上有同一系统版本的测试设备时，可额外只读获取报告涉及的系统库；普通远程用户无需执行：

```powershell
python core/functional/core_log_report/scripts/native_symbols.py collect-system --report <报告.zip> --serial <明确的开发测试设备序列号> --ndk <NDK目录> --output <系统库归档>
python core/functional/core_log_report/scripts/native_symbols.py symbolize --report <报告.zip> --symbols <App符号>/symbols.json --system-symbols <系统库归档>/symbols.json --ndk <NDK目录> --output <结果.txt>
```

该步骤只尝试公开系统路径，不提权、不读取用户私有文件、不上传库文件。二进制不放入用户报告。APEX目录不可遍历时尝试已知公开库路径；没有权限或Build ID不匹配则保持缺失，不使用另一个Android版本替代。

已有厂商或AOSP构建产出的匹配未剥离.so调试符号时：

```powershell
python core/functional/core_log_report/scripts/native_symbols.py import-symbols --report <报告.zip> --ndk <NDK目录> --search-root <厂商或AOSP符号目录> --output <匹配符号归档>
python core/functional/core_log_report/scripts/native_symbols.py symbolize --report <报告.zip> --symbols <App符号>/symbols.json --system-symbols <匹配符号归档>/symbols.json --ndk <NDK目录> --output <结果.txt>
```

工具区分SOURCE_LINE（源码行）、FUNCTION_ONLY（精确库函数）、BINARY_MATCHED（库已匹配但无调试函数）和UNRESOLVED，另保留REPORTED_FUNCTION（系统报告函数）及偏移。仅有剥离后的系统库不能还原不存在的源码行信息。输入限制2MiB/128帧，拒绝符号文件摘要错误及归档外路径。


## 全App悬浮模式（2026-11-23）

提示不是页面底部条。FloatingReportIndicator使用Activity范围的非抢焦点TYPE_APPLICATION窗口，由Activity自己的WindowManager分配合法token，未申请SYSTEM_ALERT_WINDOW，也不显示在其他App、权限页或系统文件分享器之上。旧底部margin预留已经删除。XML+ViewBinding位于独立模块，颜色/图标/触控尺寸归本库，明暗背景中性、操作强调低饱和蓝。

跨页面保留侧边及高度比例；系统窗口和IME Insets只影响悬浮窗坐标，不改变宿主的Insets职责。拖动时忽略系统重新布局引起的自动贴边，松手再吸附。Android 10+通过公开WindowInspector按400ms检查本进程当前Activity焦点窗口，业务弹窗/连续弹窗打开后提升状态窗并更新键盘安全区，不读取窗内文本。Android 8/9由宿主窗口焦点回调补充提升，连续独立弹窗组合尚未实机验收。状态窗不抢焦点，键盘始终有更高层级。

点击打开独立日志管理页并防止重复打开；返回页面不结束采集。整理阶段显示忙碌状态并暂时禁用交互，失败恢复为可点击重试。无障碍提供状态/拖动说明以及“移到另一侧”动作；TalkBack实测另验。真实取景、扫码和其他厂家窗口组合没有因通用浮窗验证而自动获得兼容结论。


## 独立管理页面（2026-11-23）

LogReport.show(activity) 打开模块自带的独立页面，包含用途说明、操作步骤、收集开关、实时状态和历史列表。开始/结束均在后台整理并阻止重复操作；失败有重试入口。关闭收集只保存到历史，不主动发送。管理页和详情页已展示收集状态，不重复覆盖悬浮胶囊，其余业务页面继续显示。

启动时异步恢复上次未结束会话为中断历史，不自动开启新收集。点击历史可重新生成 ZIP，在详情中查看摘要、分享文件、调用邮件客户端、另存或确认删除。是否实际发出由外部应用决定，模块不把打开分享器视为上传成功。其他项目接入时需要同时提供 core_base；页面颜色和英文/简体中文文案由本模块提供。

## DevLib 接入（2026-11-23 迁入）

维护目录为 core/functional/core_log_report，公开包名 com.wkq.logreport 保持不变。依赖 core_base；不依赖 Utils、相机或业务宿主。CameraLogInfo 是可选兼容字段，普通 App 不传 cameras 即可。

源码：implementation project(':core:core_log_report')。
产物：implementation 'com.wkq.dev:core-log-report:0.1.0-SNAPSHOT'，必须保留 POM/Gradle metadata；普通宿主需同时取得传递的 core-base 及其依赖。已有同包名 Base 源码的宿主必须排除 Maven core-base，不能重复打包。

本地发布：./gradlew.bat :core:core_log_report:publishReleasePublicationToDevLibLocalRepository。产物在 build/repo；该命令不上传远端。

JitPack：根 jitpack.yml 同时安装日志库、Base、XPopup 和原有网络库，通过 `-PdevlibJitpackVersion="$VERSION"` 统一发布版本与传递依赖。远端构建成功后使用 `com.github.wukuiqing49.DevLib:core-log-report:<固定tag或提交号>`。普通本地发布仍使用上文 com.wkq.dev 坐标；已有 Base 源码的宿主还须排除 JitPack 坐标下的 core-base。2026-11-23 已推送并完成 JitPack 远端构建，固定版本 ab3007e14b（来源 ab3007e14b9ff666684ab6bd8d928595b3039e14）。构建 API 返回 ok，模块 AAR/POM/metadata 已发布；LensDock 实际在线消费后 APK 构建和 475 项 JVM 测试通过。入口：https://jitpack.io/#wukuiqing49/DevLib/ab3007e14b 。真机验证不在此次发布结论内。
日志桥接：ALog.reportSink = { tag, message -> LogReport.event("logcat", "$tag: $message") }，由宿主配置；Utils 不反向依赖本库。业务完成状态仍由宿主按真实结果调用 operation/event。
工作台入口：功能 → 文件 → 日志上报。安装不自动开启采集。原生符号工具在本库 scripts/，README 中 LensDock 原始命令为来源示例，使用时替换成当前目录与实际符号路径。
