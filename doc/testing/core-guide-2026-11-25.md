# core_guide 实现与验证（2026-11-25）

## 交付范围

新增 `core/functional/core_guide`，包名 `com.wkq.guide`，入口 `FeatureGuide`。这是 DevLib 独立功能库，不改 LensDock 的业务引导。默认 XML + ViewBinding 卡片，AndroidX Core/Lifecycle/Activity 依赖，无 Base、XPopup、宿主 feature 或悬浮窗权限依赖。库 compileSdk 37 / minSdk 26；工作台 App targetSdk 37。AAR 不设置消费 App 的 targetSdk。

支持圆形/圆角矩形高亮、延迟目标解析、上下定位与底部降级、完整换行与段落、长正文滚动、小窗口整卡滚动、全局/单步主题、自定义卡片、分步/跳过/返回、有限次呼吸/扩散及关闭动画。目标点击显式启用，业务完成由宿主判断。可选 GuideHistory 区分展示、跳过、阅读和业务成功。

窗口策略由宿主唯一负责。库只附着真实 Window 或专用叠放容器，跟踪可见系统栏、caption、cutout、IME 和强制手势区，各边取最大值，不消费或向宿主叠加 Insets。保留全屏模式、图标明暗与 padding。默认英文/简体中文，其他语言回退英文。

工作台入口 `/files/core_guide`，提供分步、全屏、长文、自定义配色/扩散、无动画及独立 Dialog 六个示例。复用 DemoActivity / DemoViewModel / Base / 工作台 XML 和 KSP 路由，库本身不需要页面 Base 或业务 ViewModel。

## 实际验证

| 用例 | 方法与结果 | 判定 |
| --- | --- | --- |
| GUIDE-SESSION | 16 个实际 View/生命周期用例分别在 Robolectric API 26、35 执行，共 32 项；覆盖回调一次、前后步骤、跳过、销毁、替换及重入、返回、目标缺失/消失、布局、长文、动画取消、真实点击、颜色对比度及小容器整卡滚动 | 通过，JVM 模拟 |
| GUIDE-GEOMETRY | 4 项纯 JVM 几何测试，包含多组角落/尺寸组合、超宽超高卡片及偏移安全边界 | 通过 |
| GUIDE-HOST | 工作台目录 6 项 JVM 测试，新增入口和功能计数已同步 | 通过 |
| GUIDE-BUILD | 库 Release AAR、工作台 Debug APK 构建，实际 compile/target 37；最终组合命令 BUILD SUCCESSFUL | 通过，构建 |
| GUIDE-LINT | lintDebug：0 errors、2 个 UseKtx 风格建议；无 API 兼容错误 | 通过，保留非阻断建议 |
| GUIDE-PUBLISH | 本地文件仓库生成 AAR、sources、POM 和 Gradle module metadata，版本 `com.wkq.dev:core-guide:0.1.0-SNAPSHOT` | 通过，仅本地 |
| GUIDE-CONSUMER | `samples/guide-consumer` 独立工程只引用发布坐标，不引用主工程模块；编译及运行依赖图检查 | 通过；最新本地 AAR SHA-256 一致 |
| GUIDE-CATALOG | 3 项模块布局 Python 测试、11 项目录工具测试；APK 清单/路由核对 23 模块、19 独立入口、6 路由组 | 通过 |
| GUIDE-SOURCE | 12 个新增 Kotlin/XML 文件中文注释、明显硬编码文案及 Base 审计：0 findings；包含消费者示例 | 通过，静态；另做语义自查 |
| GUIDE-I18N-NEW | 新库、新演示及接入说明的新增资源分离检查，EN/简中严格缺译和占位符校验：0 warnings | 通过 |
| GUIDE-AGENT | Agent 结构、配置、构建与架构静态校验通过；架构保留既有路径/动态依赖图等提示 | 通过，静态 |
| GUIDE-I18N-ALL | 全项目严格国际化：feature_demo_files 的简中目录原有 93 个 key 缺译，本轮新增 guide key 完整；原资源继续 fallback，未扩大翻译范围 | 未通过，既有缺口 |
| GUIDE-DEVICE | API 26/35/36/37 真机、沉浸式临时唤出、三键/手势、刘海、IME、RTL、200% 字体、横竖屏/分屏/桌面及 TalkBack | 未验证，项目排除设备命令 |
| GUIDE-VISUAL | 六个示例的 Android 实际截图和最终视觉效果 | 未验证，无设备/渲染证据 |

最终命令：

```powershell
.\gradlew.bat --no-daemon --continue :core:core_guide:testDebugUnitTest :core:core_guide:assembleRelease :core:core_guide:lintDebug :app:assembleDebug :feature:feature_app:testDebugUnitTest :core:core_guide:publishReleasePublicationToDevLibLocalRepository
.\gradlew.bat --no-daemon --offline -p samples/guide-consumer compileDebugKotlin verifyGuideArtifact dependencies --configuration debugRuntimeClasspath --rerun-tasks
python -B scripts/test_core_layout.py
python -B scripts/test_verify_demo_catalog.py
python -B scripts/verify_demo_catalog.py --apk app/build/outputs/apk/debug/app-debug.apk
```

## 自查与限制

- 按通用库架构、UI/交互、国际化、工程审查和功能验收方法自查：库不依赖 feature；默认按钮完整可达；Window 唯一负责人不变；回调重入不叠加遮罩；动画/监听随生命周期清理；阅读不冒充业务完成。未创建子智能体，未宣称独立评审。
- 小窗口测试采用确定尺寸的专用 FrameLayout，按真实按钮测量高度触发整卡滚动；不通过缩小正文或隐藏操作满足检查。Robolectric 的布局测量不等于实际字体和视觉验收。
- wrap_content + weight 用于 AT_MOST 卡片的自然正文高度与负剩余空间收缩，保留带原因的局部 InefficientWeight 例外；内部程序创建的覆盖层和卡片使用局部 ViewConstructor 例外，不增加无用 XML 构造函数。
- 非可见或跨窗口目标会等待并明确结束为 TARGET_UNAVAILABLE。库不自动滚动 RecyclerView、不伪造触摸、不替宿主申请权限、不自动跨 Activity 续接。宿主旋转重建后应以稳定步骤 ID 和新 View 接续；DESTROYED 结束回调不应访问已销毁界面。
- 修复过模块目录计数测试和极小窗口测试夹具；Windows 构建文件锁通过停止构建守护进程、使用单次进程解决。独立消费者强制网络刷新遇到外部 Maven TLS 握手失败，随后使用已缓存工具链离线重新编译，并校验实际消费 AAR 与最新本地产物的 SHA-256 一致；不降低 TLS 或修改网络安全。最终结果以上述成功命令及归档日志为准。
- 不执行 adb，不安装设备，不创建 CI，不提交、不推送、不远端发布；未改变已有无关工作区修改。

源码与使用：[core_guide README](../../core/functional/core_guide/README.md)。设计交接：[core-guide-2026-11-25](../design/core-guide-2026-11-25.md)。证据目录：`doc/testing/evidence/2026-11-25-core-guide/`。

当前结论：代码、构建、JVM、静态和本地消费完成；设备及视觉验收未完成，全项目严格国际化保留上述既有缺口。


后续已完成 V3 页面及浮层重做并在授权 OPPO 上核对，最新结果见 [V3 验证记录](core-guide-v3-2026-11-25.md)，本页保留初始实现历史。
