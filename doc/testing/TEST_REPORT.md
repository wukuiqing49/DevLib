# DevLib 最新验证报告

日期：2026-10-23。Google 能力统一接入 core_google，旧分散认证、Billing、Entitlement 库保持移出。当前 **19 个模块、18 个业务入口、5 个路由分组**。8 个基础模块无 Git 源码差异。

| 项目 | 状态 | 结果 |
| --- | --- | --- |
| Google Billing JVM | 本次通过 | 25 项：缓存 11、状态 4、策略 10；0 失败、0 错误、0 跳过；不覆盖真实 Google/Firebase 登录 |
| core_google Release AAR | 本次通过（构建） | 独立生成库产物，不依赖 app/feature |
| App Debug / AndroidTest APK | 本次通过（构建） | 首次完整构建 5 分 27 秒；最终页面微调后增量构建与发布 1 分 17 秒；未安装或运行设备用例 |
| APK 目录与边界 | 本次通过 | 19 模块、18 入口、5 组 SPI；Google 登录/Firebase 包装/BillingManager 均已打包；移出模块、广告/Analytics 包及人像模型无残留 |
| Manifest | 本次通过（静态） | Billing 权限与 FirebaseInitProvider 合并；不附带真实 Firebase 配置，Firebase 自动初始化仍由宿主资源控制 |
| 目录策略测试 | 本次通过 | 4 项 |
| 本地 Google 发布 | 本次通过 | core-google 发布至 build/repo；POM 以 compile 暴露 Firebase Auth，并导入 Firebase BOM；其他内部 SDK 保持 runtime |
| 独立发布消费者 | 本次通过 | 45 秒；直接编译 FirebaseUser 与 GoogleBillingRuntimeConfig，未在消费者另加 Firebase SDK 依赖 |
| Agent / 配置 / 架构 / 构建 / Google UI 静态检查 | 本次通过 | 架构有 2 条已有启发式警告；构建、Google UI 均 0 条 |
| 其他保留库 JVM、接收页模拟 | 历史通过未复测 | 上轮证据见 evidence/2026-10-23-scope-prune；本轮不将其计入 25 项 |
| 设备与真实业务链路 | 未执行 | 用户明确不用真机测试；未执行登录、购买、恢复、退款、视觉、字体缩放、旋转和 Insets 验证 |

## 可复核证据

[本轮摘要与 APK SHA-256](evidence/2026-10-23-google/summary.json)；同目录保留脱敏 JUnit XML（移除机器路径和 JVM properties）及 JVM 统计。

本地完整日志：.ai-work/google-integration.log、google-publish.log、google-consumer.log。使用 Gradle 正常增量机制，不宣称每项任务都强制重跑。没有可靠工作区源码指纹，不推进 TEST_BASELINE.json，APK 哈希不冒充源码指纹。

## 执行命令

```powershell
.\gradlew.bat :core:core_google:testDebugUnitTest :core:core_google:assembleRelease :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon "-Pkotlin.compiler.execution.strategy=in-process" --console=plain
.\gradlew.bat :core:core_google:publishReleasePublicationToDevLibLocalRepository :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon "-Pkotlin.compiler.execution.strategy=in-process" --console=plain
.\gradlew.bat -p samples/published-consumer compileDebugKotlin --no-daemon "-Pkotlin.compiler.execution.strategy=in-process" --console=plain
python -B scripts/verify_demo_catalog.py --apk app/build/outputs/apk/debug/app-debug.apk
python -B scripts/test_verify_demo_catalog.py
python -B .agents/scripts/validate_agent_structure.py --project-root .
python -B .agents/scripts/validate_android_workflows.py --project-root . --config-only
python -B .agents/skills/android-project-architecture-workflow/scripts/validate_architecture.py --project-root .
python -B .agents/skills/android-build-workflow/scripts/validate_build_output.py --project-root .
python -B .agents/skills/android-ui-workflow/scripts/validate_ui_output.py --module-src feature/feature_demo_google/src/main/java --module-res feature/feature_demo_google/src/main/res
```

接入说明见 [core_google README](../../core/core_google/README.md)。真实 OAuth/Firebase 项目、签名、Play 测试渠道与商品由消费宿主配置。客户端购买状态不替代服务端验单，本地编译通过不等于生产授权体系验收。
