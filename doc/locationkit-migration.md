# LocationKit 迁入与维护交接

## 范围与来源

用户于 2026-11-02 明确要求将 LocationKit 移入 DevLib，今后在此维护。来源 https://github.com/wukuiqing49/LocationKit ，快照提交 `5ed25dea327c3360ba8e559ba84aa0981df9c296`。原仓库没有修改、删除、归档或推送。

| 原目录 | 新源码模块 | 包名 | 职责 |
| --- | --- | --- | --- |
| lib_location | core/functional/core_location | com.wkq.location | 经纬度、传感器融合、轨迹与可选前台服务 |
| lib_address | core/functional/core_location_address | com.wkq.address | 可选 GeoNames 离线地名，约 16.1 MB 数据库 |

不导入原 Base、App、APK、二次打包 AAR、Wrapper 或其他产品配置。保留公开类型与方法名称，Gradle 使用 `:core:core_location` 与 `:core:core_location_address`。本仓继续使用 compileSdk 37 / minSdk 26 / JVM 17；不再同时引用旧 Location / LocationAddress Maven AAR。

来源哈希记录在 docs/import-manifest.json；这些摘要描述迁入前的原始文件，不表示本仓经适配后的源码与原文件逐字相同。数据库原资源保持字节一致，迁入原始源码中的作者信息保留。

## 工程与必要修复

- 采用本仓集中依赖与 library-publish 发布脚本，不修改基础框架。
- 无真实位置时返回失败；停止和替换请求清理超时/回调；近似权限与撤权处理，位置广播限于宿主包；过滤开关按配置生效。
- SensorService 修复未初始化字段销毁崩溃，补位置 FGS 类型并持有实际融合管理器；不自动打开设置，不自动恢复后台采集。
- 步行推算等待真实位置起点；未授权步数能力时不注册对应传感器；停止释放回调，轨迹 View 离开窗口移除动画。
- 离线数据库首次复制使用共享锁和 AtomicFile；宿主必须将构造与查询放在 IO，关闭帮助类。
- 迁入及改动声明补中文 KDoc；通知/结果与新增 Demo 提供适用的中英文资源。

## 页面设计与交互交接

目标用户是验证和接入库的开发者。沿用工作台 Demo 的语义颜色、文字层级、按钮与滚动布局，不复制原产品界面。入口在功能分类的“定位与地名”，两个模块各有独立路由及接入详情。

- 经纬度页：说明 → 主操作“获取单次位置” → 必要时用途解释与系统授权 → 等待/真实坐标/失败结果。次操作停止，拒绝后可重试或按提示去宿主设置；不自动请求后台位置。结果保存在 SavedStateHandle，页面离开即取消，不恢复在途定位。避免双击启动。
- 地址页：用途和近似精度说明 → 经纬度输入 → 主操作查询 → 最近地名/无覆盖/输入错误。构造与查询在 DemoViewModel 的 IO 任务机制内，忙碌时禁用查询，使用现有取消机制；系统保存输入，结果保存至 SavedStateHandle，不硬编码默认坐标。
- 公共复用：DemoActivity → BaseVMTitleActivity、DemoViewModel → BaseViewModel、XML + ViewBinding、Demo.Page / Label / Button / Primary 及 demo_output。普通页面采用 Base 的 Edge-to-edge 和唯一 Insets owner；没有全屏需求，不新增沉浸式或另一套窗口封装。
- 导航：沿用工作台现有 Router。两个独立库不依赖 Router，普通消费项目使用自己的简单导航即可。

屏幕大小、长英文/字体缩放、深浅主题、RTL、旋转及多窗口按自适应滚动布局与系统状态恢复进行源码审查；实际系统栏/键盘/刘海、权限交互与视觉未在设备验证。不将静态 UI 校验当作实际画面通过。

## 验证与剩余范围

实际命令与细分结果见 doc/testing/TEST_REPORT.md 和 evidence/2026-11-02-locationkit/summary.json。Debug App、两库 Release AAR/POM、Robolectric 和目录测试及 APK 路由检查通过；未发布远端产物，也未更新其他 App 正在使用的旧 AAR。

尚未验证真实 GPS/网络 Provider、定位精度、近似权限真实设备行为、传感器误差、后台服务与权限撤回，以及 API 26/37/多窗口/视觉。原始算法的存在不等于这些能力或数据覆盖得到生产验收。按项目约定未执行 adb，测试自动基线不推进。
