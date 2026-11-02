# DevHub 私有 SDK：功能与配置

[返回项目首页](../README.md#devhub-基础统计)


DevHub 已在独立私有仓库维护，消费项目直接依赖 SDK，DevLib 不额外包装或计入模块数量，工作台不自动初始化或上报。

## 基础统计功能

| 能力 | 内容 |
| --- | --- |
| 安装统计 | SDK 管理安装身份和首次启用注册 |
| 日活统计 | SDK 按 UTC 日去重，处理生命周期与重试 |
| 手机信息 | 自动获取厂商、型号、Android 版本 |
| App 信息 | 自动获取 App 版本 |
| 国家与语言 | 自动获取 Locale 区域码和语言标签，无需手填 |

以下示例使用 CORE_ONLY，仅接入安装、日活和基础字段，不接入自定义事件、支付权益、活动页或反馈图片。Locale 国家可能为空，不代表真实位置、IP 或 SIM 国家，不需要定位或电话权限。

## 私有依赖与发行配置

依赖与 API 按 DevHub v1.2.0 核对。以下配置加在消费项目，本仓库没有自动添加依赖。

```groovy
// settings.gradle：合并到 dependencyResolutionManagement.repositories
maven {
    url = uri('https://jitpack.io')
    credentials {
        username = providers.gradleProperty('authToken')
            .orElse(providers.environmentVariable('JITPACK_AUTH_TOKEN')).orNull
    }
    content { includeModule('com.github.wukuiqing49', 'DevHub') }
}

// App build.gradle 的 dependencies；已有版本目录时集中管理坐标与版本
implementation 'com.github.wukuiqing49:DevHub:1.2.0'
```

已有普通 JitPack 仓库时，对普通入口添加 `content { excludeModule('com.github.wukuiqing49', 'DevHub') }`，使该坐标使用认证入口；其他私有依赖保留原认证方式。

| 参数 | 配置方式 |
| --- | --- |
| 下载 token | 用户级 Gradle 属性 authToken，或环境变量 JITPACK_AUTH_TOKEN；仅构建下载使用，不进入 APK |
| API 地址 | 宿主配置部署的 HTTPS API 根地址，不使用 CMS 页面地址 |
| App Key | 按发行应用配置，会进入 APK；不是管理员密钥，与下载 token 不同 |
| 有效授权状态 | 宿主恢复已有状态，控制开启及撤回 |

## Application 最小接入

合并到已有 Application，在 onCreate 初始化一次并持有同一实例，不新增管理器、页面或路由。示例地址和 Key 是占位值，替换为当前发行应用配置；也可通过宿主 BuildConfig/flavor 提供。

```kotlin
import com.devhub.sdk.AnalyticsMode
import com.devhub.sdk.DevHub
import com.devhub.sdk.DevHubConfig

private lateinit var devhub: DevHub

/** 初始化基础统计，自动获取设备和 Locale 字段，等待宿主恢复授权。 */
private fun initializeDevHub() {
    devhub = DevHub.initialize(
        applicationContext,
        DevHubConfig(
            apiUrl = "https://your-devhub-api.example",
            appKey = "YOUR_APP_KEY",
            collectionEnabled = false,
            analyticsEnabled = true,
            analyticsMode = AnalyticsMode.CORE_ONLY,
            collectDeviceInfo = true,
            collectLocale = true,
            allowedEventProperties = emptySet(),
            allowFeedbackImages = false,
        ),
    )
    // 非空时接入宿主诊断，不能视为初始化成功。
    devhub.initializationError?.let { android.util.Log.e("DevHub", it) }
}

/** 首次授权、恢复有效授权或撤回时调用，不与 Google 登录和付费状态绑定。 */
private fun updateDevHubConsent(validConsent: Boolean) {
    devhub.setCollectionEnabled(validConsent) { result ->
        if (!result.successful) {
            android.util.Log.e("DevHub", result.error ?: "collection_change_failed")
        }
    }
}
```

上述配置默认自动获取机型、国家和语言，不需要宿主逐项传入。SDK 1.2.0 自身的 collectLocale 默认为 false、统计模式默认为 STANDARD，因此直接接入时需保留示例中的 `collectLocale=true` 和 `CORE_ONLY`，不能把已移除包装层的默认值当作 SDK 默认值。

SDK 自行处理安装身份、日活去重和重试，不另建定时器或设备标识、不在每个页面重复初始化。配置/App Key 冲突按 SDK 的 close/重建流程统一管理；撤回调用 setCollectionEnabled(false) 并处理失败，close 不代替撤回，也不删除服务端历史数据。

保留上游 POM 的传递依赖，基础统计模式不会裁剪整个 SDK。消费项目需核实 Manifest、备份规则、设备运行及后台安装/日活记录。初始化或采集开关回调成功不代表数据送达；本文不代表真实上报、设备运行或最新版本已验证。
