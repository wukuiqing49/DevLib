# Android 17 配置与兼容边界

更新：2026-10-26。

## 当前基线

| 项目 | 配置 |
| --- | --- |
| compileSdk / targetSdk | 37 / 37 |
| App minSdk / JDK | 26 / 17 |
| AGP / Gradle | 9.1.1 / 9.3.1 |
| Kotlin / KSP | 2.2.10 / 2.2.10-2.0.2 |

实际配置入口为 `gradle/libs.versions.toml`、Wrapper 和根构建脚本；根 appConfig/compileSdkVersion/targetSdkVersion 读取同一版本目录。迁入版本目录同步工具版本和功能库 SDK。`.agents/config/project-profile.yml` 与 `project-scaffold.yml` 同步此基线，新建项目不再默认 API 36。

为保持基础库实现不变，AGP 9.x 暂时保留 `android.builtInKotlin=false` 与 `android.newDsl=false`。这是官方支持的迁移过渡，会产生弃用警告；AGP 10 前需整体迁移 Kotlin、DSL 与相关插件。XPopup 自身固定 compileSdk 36 保留，宿主 targetSdk 37；独立库无需只为宿主升级而更改源码或公开 API。

## 宿主权限适配

App 声明 `ACCESS_LOCAL_NETWORK`；直接局域网共享在启动前说明并申请。上传、远端连接和恢复任务按目标主机判断，本地文件和识别为公网的目标不强制依赖该权限。域名判断在 IO 线程解析，局域网地址包含私有地址、链路本地地址、IPv6 ULA 和 .local 名称。

权限拒绝取消操作并给出设置入口说明；授权回调不保留跨页面重建的操作闭包，重建后提示用户重新点击。网络权限撤销后，重新操作会重新检查。任务恢复按持久化任务目标检查，不使用当前表单猜测目标。引擎库不承担宿主权限 UI。

DNS 结果、企业网络、权限撤销时正在运行的任务和后台恢复仍需 API 37 运行验证；错误归入现有页面错误流程。本次不把主机分类视为系统权限的替代。

## Skill 与门禁

构建、脚手架、架构、UI、功能测试 Skills 按任务加载 `.agents/skills/android-build-workflow/references/android-17.md`，覆盖：

- 所有运行于 Android 17 的应用与仅 target 37+ 的行为变化分别核验。
- 局域网权限、大屏可调整尺寸、旋转、多窗口、Insets 与状态恢复。
- MessageQueue 私有反射、static final 修改、外部 URI 授权、WebView/TLS 和内存相关变化按能力检查。
- 原有 Edge-to-edge、FileProvider、16KB native 检查继续保留。

构建门禁拒绝 API 37 使用旧 AGP、AGP 9.1 使用旧 Gradle/Kotlin、compileSdk 低于 targetSdk，以及关闭内置 Kotlin 却仍启用新 DSL 的不兼容配置。生成器在写入前拒绝不支持的工具组合；自测只写临时目录，不覆盖当前工程。

## 验证边界

本轮构建、JVM、脚本与静态检查结果见 `testing/TEST_REPORT.md`。未执行真机或模拟器、视觉验收、真实登录购买、生产服务事务、release/R8 和重新发布消费者验证；基础库源码保持原样。现有 XPopup 的私有 View listener 反射保留，不能以文本扫描推断运行安全。

没有创建或修改 CI，没有提交或推送。

## 官方依据

- [API 与 AGP/Gradle 支持表](https://developer.android.com/build/releases/about-agp)
- [AGP 9.1.1 兼容信息](https://developer.android.com/build/releases/agp-9-1-0-release-notes)
- [Kotlin 迁移及过渡开关](https://developer.android.com/build/migrate-to-built-in-kotlin)
- [Android 17 变更清单](https://developer.android.com/about/versions/17/summary)
- [target 37 行为变更](https://developer.android.com/about/versions/17/behavior-changes-17)
- [局域网权限](https://developer.android.com/privacy-and-security/local-network-permission)
