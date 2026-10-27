# core_google

Google SDK 功能适配库，登录与计费来自 DropLAN，评分策略参考 SiteReport / PressIPTC 并去除产品耦合，保留 `com.wkq.google` API 包名。属于功能库，不是基础库。

## 范围

- `GoogleSignInCoordinator`：Credential Manager Google 登录与凭据清理。返回的本地 token claims 只供显示，必须交给 Firebase 或服务端验证。
- `DropGoogleAccount`：通过 Firebase Authentication 验证 Google ID token，获取/退出/删除 Firebase 账号。
- `GoogleBillingRuntime` / `GoogleBillingManager`：Play 商品查询、订阅与非消耗型一次性购买、订单确认补偿、恢复及离线状态缓存。
- `GoogleRateManager` / `GoogleRatePolicy`：可配置的使用时长、关键操作与冷却策略，以及 Google Play 应用内评分请求。
- `GoogleBillingGateway` 是状态查询接口；不包含商业后台、服务端验单或跨设备统一授权系统。当前一次性商品按永久解锁处理，不用于消耗型商品。

不包含广告、Analytics、DevHub、原产品页面、商品 ID 或业务 Pro 门禁。不依赖其他 DevLib 模块。旧 lib_auth_credentials、lib_auth_firebase、lib_billing_play、lib_entitlement 已移出，不再并行维护。

## 接入

源码依赖 `implementation project(':core:core_google')`，发布坐标 `com.wkq.dev:core-google:0.1.0-SNAPSHOT`。Android minSdk 26、JDK 17；依赖快照见 `gradle/catalogs/googleLibs.versions.toml`。

普通 Google 登录：宿主配置自己的 Android OAuth 包名、签名与 Web Client ID，在 Activity 的生命周期协程中调用 `GoogleSignInCoordinator.signIn(activity, webClientId)`。不要记录或展示 ID token。

Firebase 登录：宿主配置自己的 Firebase 项目，启用 Google 提供商。由宿主选择 google-services 插件与本地 google-services.json，或显式 FirebaseOptions 初始化默认 FirebaseApp。库不应用该插件，不附带配置文件。调用 `DropGoogleAccount.signIn(activity, webClientId)` 后通过 `currentUser` 获取会话。删除账号是破坏性操作，Demo 不提供该按钮。

Play Billing：宿主提供商品 ID，显式初始化，不需要 Firebase 会话：

```kotlin
GoogleBillingRuntime.initialize(context, GoogleBillingRuntimeConfig(
    billingInAppProductIds = yourNonConsumableProductIds,
    billingSubscriptionIds = yourSubscriptionIds,
    requireAppAccount = false
))
val products = GoogleBillingManager.queryProducts(yourSubscriptionIds, GoogleProductType.SUBS).getOrThrow()
// 明确展示 Play 返回的价格、周期并取得用户操作后：
GoogleBillingManager.launchPurchase(activity, selectedProduct.productId, selectedProduct.offerToken)
// 页面生命周期内订阅购买结果，用完关闭；后续恢复实际购买状态。
val registration = GoogleBillingManager.addPurchaseUpdatedListener { response, purchases -> /* 更新页面 */ }
val entitlement = GoogleBillingManager.refreshEntitlement()
registration.close()
```

`requireAppAccount` 默认 true；有自有账号时提供稳定 `billingAccountIdProvider` 与 `billingPurchaseAllowed`，不要把 Google/Firebase 账号和 Play 购买默认视为同一会话。回调可能在后台线程。离线缓存是客户端恢复策略，不能代替服务端验单。正式商品与订阅披露、退款/撤销同步、安全授权策略由宿主维护。

## Demo 与验证

`feature_demo_google` 路由 `/google/core_google`：输入 Web Client ID → 选择登录方式 → 查看/退出 Firebase 会话；输入一个商品 ID → 选择订阅或一次性类型 → 查询并选择报价 → 显式确认购买 → 恢复查询。

未配置 Firebase 时给出接入提示；无可用报价时不能购买。不自动连接或购买，不输出 token。离开或旋转页面取消持有 Activity 的登录任务。演示使用 `requireAppAccount=false`，不代表消费项目必须采用同样策略。

本仓库不执行真机登录、购买或删除账号测试。来源与原文件哈希见 `docs/import-manifest.json`；本地编译和 JVM 测试见 `doc/testing/TEST_REPORT.md`。

## 评分提示与独立反馈

来源：SiteReport 与 PressIPTC 的 rate 目录，来源提交和原文件摘要见 docs/import-manifest.json 中 core_google.ratingSources。采用 SiteReport 默认 1 天使用时长、1 次关键操作、30 天请求间隔；这些都是宿主可配置的本地策略，不是 Google Play 配额。

```kotlin
// 宿主尽早在自身生命周期协程初始化首次使用时间，不依赖登录或计费。
GoogleRateManager.initialize(context)
// 在真实关键操作完成后记录稳定操作 ID；最近 128 个 ID 哈希去重。
GoogleRateManager.recordCompletedAction(context, operationId)
// 页面恢复到前台且处于自然业务结束节点时调用；不放在“去评分”按钮后。
val result = GoogleRateManager.requestIfEligible(activity, GoogleRatePolicy(
    minDays = 1, daysBetweenPrompts = 30, minCompletedActions = 1
))
// 用户选择不再自动提示时设置；不会标记用户已评分。
GoogleRateManager.setSuppressed(context, true)
```

`initialize`、状态读写和请求都是 suspend 方法；存储在 IO 串行提交，请求在主线程且要求宿主处于 RESUMED（普通 Activity 要有窗口焦点）。异步取回 ReviewInfo 后重新检查页面状态。取消会继续以协程取消传播；已打开的 Play 流程未完成前仍阻止重入，不强行关闭 SDK 窗口。

请求前预留计数与冷却；API 失败也不立即重试。`FLOW_FINISHED` 只表示 Task 流程结束，不能判断卡片展示、星级或提交。首次使用从调用 initialize/snapshot/record 的时间开始，不自动推断安装日期。完成事件去重窗口为最近 128 项，超出窗口的旧 ID 再次上报可能重新计数；宿主仍负责真实业务事件。

不迁入高星去商店、低星发邮件的分流弹窗，不提供 `isRated` / `markRated`。旧 google_rate_state 的“已评分”值不会自动迁移。用户主动点击商店评分与独立反馈，由宿主复用 core_utils.IntentUtil.openMarket / sendEmail；不会根据用户态度筛选请求，也不会在自动请求失败后强制跳转。库本身没有新增 Base/Utils 依赖。

工作台第三段可检查条件、模拟一次关键操作并按正式策略请求、关闭/恢复提示、主动打开商店、输入邮箱发送独立反馈。演示不会绕过一天等待或 Play 配额，没有假造“评分成功”结果。

参考：[官方接入](https://developer.android.com/guide/playcore/in-app-review/kotlin-java)、[请求时机](https://developer.android.com/guide/playcore/in-app-review)、[官方 FakeReviewManager 测试边界](https://developer.android.com/guide/playcore/in-app-review/test)。本地假管理器不显示 UI、不提交评分，仍需有 Play 分发资格的设备验收。

## 可靠性与接入边界（2026-10-27）

订阅查询只支持当前宿主计费账号；非空 userId 必须与 billingAccountIdProvider 一致，查询期间切换账号明确失败。永久购买不再计为订阅；未知或部分失败且无法确认订阅时抛出 IllegalStateException，宿主保留旧状态并提供重试。expireTimeMillis=0 仅表示未知，真实到期时间由可信服务端提供。旧方法签名保留。

上传入参、商品配置、账号切换及登录/计费衔接继续由宿主维护；本轮没有新增服务端验单、消耗型商品或多阶段优惠报价。评分完成仍不代表已提交评价。
