# core_location：LocationKit 定位与轨迹

源码来自 [LocationKit](https://github.com/wukuiqing49/LocationKit)，快照提交 `5ed25dea327c3360ba8e559ba84aa0981df9c296`，对应原 `lib_location`。后续在 DevLib 维护，保留公开包名 `com.wkq.location`，不复制原项目 App、Base、APK 或 LibAAR。来源文件摘要见 `docs/import-manifest.json`。

## 能力与接入

- Android LocationManager：GPS、网络、被动 Provider、有效缓存；FAST / SINGLE / FUSION / LOCATION_NET / LOCATION_GPS 模式。
- 超时、位置抖动过滤及可选宿主包内广播；无真实位置时失败，不能把默认坐标当作定位成功。
- 原有传感器支持检测、GPS/PDR/气压融合、卡尔曼平滑、轨迹数据及 TrackMapView 动画保留；它们是估算能力，不承诺测绘精度。
- 经纬度获取无需第三方地图 Key，不依赖 Google Play Services、AMap 或原项目 Base。
- 可选离线地名通过独立 `core_location_address` 使用，不强制携带数据库。

```groovy
implementation project(':core:core_location')
// 发布到可访问仓库后使用 Maven 坐标；不与上面的源码引用同时添加
// implementation 'com.wkq.dev:core-location:0.1.0-SNAPSHOT'
```

不要再同时引入 `com.github.wukuiqing49:Location`，避免重复包和类。当前 DevLib 编译基线为 compileSdk 37 / minSdk 26 / JVM 17，消费项目仍须独立核实 SDK 与依赖兼容。

## 最小调用与生命周期

```kotlin
import com.wkq.location.LocationKit
import com.wkq.location.address.LocationConfig
import com.wkq.location.address.LocationType

// 宿主在可见页面获取运行时定位授权后，于主线程调用。
LocationKit.init(applicationContext, LocationConfig()
    .setLocationType(LocationType.SINGLE)
    .setTimeout(10_000L)
    .setUseBroadcast(false))
LocationKit.startLocation { result ->
    val location = result.location
    if (result.success && location != null && location.provider != "default") {
        // 使用 latitude / longitude / accuracy，实际精度由设备与环境决定。
    } else {
        // 展示宿主自己的本地化失败与重试入口，不把默认坐标写入业务数据。
    }
}
// 用户停止、页面不可见或任务完成后，释放本页拥有的请求。
LocationKit.stopLocation()
```

LocationKit 是进程级单例，维护一个当前请求；新请求替换旧请求，不支持多个页面独立并发监听。宿主统一管理所有权，主线程启动/停止，页面重建不自动重新定位。回调不应抛异常；不要在停止后保留 Activity 引用。

LocationConfig 默认 FUSION、6 秒超时、5 秒最小间隔、1 米最小距离；默认坐标仅用于失败数据兼容。过滤选项与距离、广播开关由任务配置，建议宿主显式选择 SINGLE 或连续模式。

## 权限与可选后台服务

Manifest 声明精确/近似位置；宿主在使用时请求授权并处理拒绝、永久拒绝和系统定位关闭。近似授权不能承诺 GPS 精确坐标，不因功能迁入而自动申请后台位置。

可选 SensorService 保留，声明 `exported=false` 和 `foregroundServiceType=location`，包含 FOREGROUND_SERVICE / FOREGROUND_SERVICE_LOCATION。宿主仅在满足系统可见性与定位授权要求时显式启动；ACTIVITY_RECOGNITION 在使用步数传感器时由宿主按需授权，通知交互亦由宿主处理。本库不自动打开设置或申请后台权限。

后台位置场景由消费项目自行声明并验证 ACCESS_BACKGROUND_LOCATION、前台服务启动限制及其他所需权限；单纯声明 Service 不代表后台运行已验证。融合管理器使用前先初始化 LocationKit，结束时调用 stop；轨迹 View 离开窗口会取消动画任务。

## 迁入时的必要修复

- 超时无位置和 FAST 无缓存不再把默认坐标当成功；停止/替换请求清理待执行任务与回调。
- 近似权限下跳过需要精确权限的 GPS 注册，撤权时安全清理；广播限定宿主包。
- SensorService 改为持有并释放实际融合管理器，避免销毁时访问未初始化 sensorHelper；补齐位置前台服务类型，不从服务自动打开设置。
- 轨迹 View 释放动画；融合管理器不在首次真实位置之前通过步行事件生成坐标，停止时释放回调。
- 通知及定位结果提供英文默认与简体中文资源；其他原有公开类型和算法保留。

## 验证入口

### Android 17 / API 37

- 工程使用 compileSdk / targetSdk 37，最低兼容 API 26；不通过降低目标版本规避系统限制。
- 位置交付前重新检查运行时授权，临时授权撤回后丢弃排队坐标、停止请求并报告失败。
- SensorService 通过 AndroidX ServiceCompat 显式使用 location 类型，未授权时停止；系统拒绝前台提升时记录不含坐标的诊断并停止，不继续注册传感器。宿主调用 `startForegroundService` 本身仍可能被系统拒绝，调用方应处理异常；只能在符合系统要求的可见场景启动，不从后台或开机广播强行拉起。启动请求被接受不等于服务已运行。
- 不增加后台位置、局域网或通知运行时权限申请；步数、通知展示与后台场景由宿主按真实需求处理。离线地名库不需要定位权限。
- 使用公开 Handler/Looper API，不反射 MessageQueue；两个库不包含 native 代码。工作台沿用既有 Base 的边到边与窗口处理，本轮未修改页面布局。

规则依据：[Android 17 行为变更](https://developer.android.com/about/versions/17/behavior-changes-17)、[位置类型前台服务](https://developer.android.com/develop/background-work/services/fgs/service-types#location)。前台服务权限约束来自此前系统版本，在 API 37 上仍须遵守，不属于新增的 Android 17 权限。

自动化回归使用 Robolectric API 26/35，API 37 使用本地 SDK 编译与静态检查；不能把它们标记为 Android 17 设备运行验收。

```powershell
.\gradlew.bat :core:core_location:testDebugUnitTest :core:core_location:assembleRelease
```

工作台路径：功能 → 定位与地名 → 经纬度定位。当前无 adb/真机验收，GPS/网络 Provider 可用性、真实精度、传感器轨迹、后台服务、API 26/37 和多窗口视觉均需实际验证。编译和 Robolectric 测试不等于这些能力已通过。
