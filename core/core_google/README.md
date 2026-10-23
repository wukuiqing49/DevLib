# core_google

Google SDK 功能适配库，来自 DropLAN 当前启用的 auth/billing 子集，保留 `com.wkq.google` API 包名。属于功能库，不是基础库。

## 范围

- `GoogleSignInCoordinator`：Credential Manager Google 登录与凭据清理。返回的本地 token claims 只供显示，必须交给 Firebase 或服务端验证。
- `DropGoogleAccount`：通过 Firebase Authentication 验证 Google ID token，获取/退出/删除 Firebase 账号。
- `GoogleBillingRuntime` / `GoogleBillingManager`：Play 商品查询、订阅与非消耗型一次性购买、订单确认补偿、恢复及离线状态缓存。
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
