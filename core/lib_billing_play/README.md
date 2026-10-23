# lib_billing_play

Google Play 商品、购买、恢复与购买状态。来源：NASFileManager。类型：Android Library / AAR。

## 接入

```groovy
implementation project(':core:lib_billing_play')
```

独立发布坐标：`com.wkq.dev:lib-billing-play:0.1.0-SNAPSHOT`。运行 `:core:lib_billing_play:publishReleasePublicationToDevLibLocalRepository` 后，在消费工程声明 DevLib 的 `build/repo` 文件仓库；不要同时引入原库的 AAR。

## 宿主责任与边界

初始基线取自 NAS 的支付核心；七份副本尚未完成语义合并，不迁入原付费 UI、商品 ID 或具体收费规则。

宿主通过 `GoogleBillingRuntime.initialize(context, GoogleBillingRuntimeConfig(...))` 提供商品、方案、购买开关和账号标识。`requireAppAccount` 默认开启；`offlineCacheMaxAgeMillis` 默认沿用基线的 24 小时，设为 0 可禁止离线缓存授权。服务端购买核验和具体权益映射仍由宿主负责。

## 验证状态

来源与初始文件摘要见根目录 `docs/import-manifest.json`。迁入后验证统一记录在 `doc/testing/TEST_REPORT.md`；有源码或产物不等于完成真机、跨平台或商用验收。原宿主尚未回接，不宣称迁移闭环完成。
