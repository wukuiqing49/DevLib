# core_location_address：可选离线地名

来自 [LocationKit](https://github.com/wukuiqing49/LocationKit) 的 `lib_address`，快照 `5ed25dea327c3360ba8e559ba84aa0981df9c296`。后续在 DevLib 维护，保留 `com.wkq.address.GeoDbHelper` 与公开查询 API，不依赖 core_location 或宿主模块。

## 功能与依赖

按经纬度查询 GeoNames 预置数据库中的最近/附近地名，返回名称、行政区、坐标及近似距离。没有定位、在线地图、第三方 Key 或联网要求，不需要定位权限。数据库资源约 16.1 MB（文件 16,089,088 字节），仅需要经纬度时不要添加本库。

```groovy
implementation project(':core:core_location_address')
// 远端发布后才使用以下坐标，不与源码同时引用
// implementation 'com.wkq.dev:core-location-address:0.1.0-SNAPSHOT'
```

不要同时依赖旧 `com.github.wukuiqing49:LocationAddress`，避免重复类与数据库资源。DevLib 基线 compileSdk 37 / minSdk 26 / JVM 17。

## 查询与释放

```kotlin
import com.wkq.address.GeoDbHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 构造函数首次复制数据库，构造和查询整个过程都放到 IO。
val nearest = withContext(Dispatchers.IO) {
    GeoDbHelper(applicationContext).use { helper ->
        helper.getNearestPlaceResult(latitude, longitude)
    }
}
// nearest 为 null 表示当前搜索窗口没有数据；distanceKm 单位为千米。
```

宿主先校验纬度 -90..90、经度 -180..180 且均为有限数值；查询结果用于附近地名提示，不能当作门牌地址或真实定位精度。radius 默认 0.05，是经纬度窗口的角度范围，不是公里；数据覆盖由预置数据库决定，不保证所有国家地区或街道。

保留 getNearestPlace / getNearbyPlaces 字符串接口，以及 Result / Async 变体。Async 查询不会把构造函数的首次复制自动移到后台，仍应遵循上述整体 IO 示例。

数据库原始资源保留，首次复制新增共享锁和 AtomicFile，复制失败不留下半份正式数据库；数据版本与后续迁移策略需随资源更新维护。源数据归属、说明和原作者信息保留在源码/来源记录，发布时一并核对适用的数据分发要求。

工作台路径：功能 → 定位与地名 → 离线地址查询。支持输入坐标、错误反馈、无覆盖提示与结果距离，不自动读取当前位置。

```powershell
.\gradlew.bat :core:core_location_address:testDebugUnitTest :core:core_location_address:assembleRelease
```

测试使用真实迁入数据库做本地查询，不代表全球覆盖、业务地址精度或设备验收完成。
