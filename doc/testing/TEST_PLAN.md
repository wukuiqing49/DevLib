# DevLib 当前测试计划：统一 Google SDK

日期：2026-10-23。当前 19 个模块：8 个基础模块、10 个功能库、1 个内部支撑库；18 个业务演示入口、5 个路由分组。基础库不改动，旧分散认证、Billing、Entitlement 库保持移出。

| 用例 | 原因 | 验证与预期 |
| --- | --- | --- |
| GOOGLE-JVM | 迁入当前启用的计费状态与策略 | core_google:testDebugUnitTest；保留上游三组测试，明确失败、错误和跳过数 |
| GOOGLE-AAR | 新模块独立发布能力 | core_google:assembleRelease、本地发布；没有 app/feature 依赖 |
| GOOGLE-CONSUMER | FirebaseUser 公开类型需传递依赖 | 独立 published-consumer 编译 FirebaseUser 与 BillingRuntimeConfig，不直接依赖 Firebase SDK |
| GOOGLE-APP | 新页面、路由与 SDK 依赖 | Debug App 和 AndroidTest APK 构建；不安装运行 |
| GOOGLE-CATALOG | 移出旧模块、统一接入与业务页注册 | APK 目录校验；19 模块、18 入口、5 组 SPI；统一 Google 类存在，广告、Analytics 及原移出能力无残留 |
| GOOGLE-RULES | 构建、架构、UI、规范同步 | Agent / 架构 / 构建 / Google 页面 UI 静态检查及目录策略测试 |

旧库代码未变化，仅依赖图与 Demo 入口变化，本轮不重复跑其全部 JVM 用例。前轮范围裁剪证据保留在 evidence/2026-10-23-scope-prune，属于历史通过未复测。

用户明确不做真机测试：设备安装、视觉/字体/旋转/Insets、OAuth 交互、Firebase 会话、Play 测试渠道报价、订阅/购买/恢复/退款均未执行。无真实项目配置、账号、密钥或商品写入仓库。编译通过不代表登录或支付链路验收通过。

未建立可靠工作区源码指纹，本轮不推进 TEST_BASELINE.json；历史证据不冒充本轮结果。
