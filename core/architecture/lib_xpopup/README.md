# lib_xpopup

公共弹窗、底部弹层、抽屉与图片查看组件。

## 定位与依赖

- 分类：架构基础库；来源：XPopup。
- 物理目录：`core/architecture/lib_xpopup`；Gradle 路径：`:core:lib_xpopup`。
- Android AAR；模块声明 minSdk 21，compileSdk 37；JVM target 1.8。这是模块声明值，实际消费下限还受传递依赖与 Manifest 合并约束。
- 本仓模块依赖：无其他 core 模块依赖。

## 主要入口与调用流程

[XPopup](src/main/java/com/lxj/xpopup/XPopup.kt)、[BottomPopupView](src/main/java/com/lxj/xpopup/core/BottomPopupView.kt)、[CenterPopupView](src/main/java/com/lxj/xpopup/core/CenterPopupView.kt)、[PopupInsetCalculator](src/main/java/com/lxj/xpopup/core/PopupInsetCalculator.kt)。

简单公共弹窗优先 Base DialogKit → 定制弹层继承对应 PopupView → 按页面生命周期显示和关闭。

保留 com.lxj.xpopup 包名与现有许可文件；宿主检查键盘、系统栏、Insets 和旋转恢复，避免与 Base 重复添加安全区。

## API 37 与沉浸式

- 独立 Dialog 使用兼容窗口控制器管理系统栏；再次显示或恢复焦点时重建自身策略，失去焦点时不改写状态。
- 默认 Auto 策略的全屏/抽屉前景避让桌面标题栏；SafeArea 模式按系统栏与刘海最大值避让，键盘移动仍由原键盘处理器负责。
- 显式 EdgeToEdge 模式保持不自动加间距，由宿主保护关闭按钮、刘海和桌面窗口控件，背景与触摸层可铺满。
- minSdk 21 声明及低版本兼容路径保留，未为新入口强制提升 AndroidX Core 到要求更高 minSdk 的版本。实际宿主下限仍取决于全部传递依赖。
- 普通页面不要为“沉浸式”隐藏导航；仅媒体预览等全屏内容使用隐藏策略。运行及视觉验证边界见测试报告。

## 接入

消费模块 `dependencies` 中添加：

```groovy
implementation project(':core:lib_xpopup')
```

## 产物发布与本地检查

标准 Maven 坐标：`com.wkq.dev:lib-xpopup:3.3.2`。本库版本来自模块 `version.properties` 的 `VERSION_NAME`；发布配置同步项目版本、POM 与 Gradle 元数据。不要同时依赖同库源码模块与旧 Maven AAR/JAR。

以下命令在 DevLib 根目录执行：

```powershell
.\gradlew.bat :core:lib_xpopup:assembleDebug :core:lib_xpopup:testDebugUnitTest
.\gradlew.bat :core:lib_xpopup:publishReleasePublicationToDevLibLocalRepository
```

本地仓库位于根 `build/repo`。有本仓模块依赖时，先运行根 `publishLibrariesToLocalRepository` 发布完整依赖和插件 marker；不要只复制一个 AAR/JAR。消费工程添加对应文件 Maven 仓库，再按标准坐标接入。

## 工作台与验证状态

实际调用示例：[PopupActivity](../../../feature/feature_demo_foundation/src/main/java/com/wkq/dev/demo/foundation/PopupActivity.kt)。入口由工作台对应分类进入；演示适配不属于本库依赖。

来源快照见 [import-manifest](../../../docs/import-manifest.json)；当前分类见 [library-catalog](../../../docs/library-catalog.json)；逐项验证及未验证边界见 [固定测试报告](../../../doc/testing/TEST_REPORT.md)。本页命令是复核入口，不代表本轮已重新执行。源码迁入、构建和 JVM 测试不等于真机、服务器、视觉或生产验收。

[返回项目说明](../../../README.md) · [库目录](../../README.md)

## 3.3.2 更新说明

- 沿原仓库最新标签版本 `3.3.1` 将补丁号加 1；compileSdk 37、弹窗独立窗口焦点恢复与桌面标题栏安全区。
- 保留公开包名及 API；使用 DevLib 坐标 `com.wkq.dev:lib-xpopup:3.3.2`，不代表原仓库 `com.github...` 坐标或标签已发布。
- 发布前核对 AAR、sources、POM 与 module metadata；构建和本地发布不代表设备、视觉或远端发布验收。
- 协作规范见根 [AGENTS.md](../../../AGENTS.md) 与 [.agents 使用说明](../../../.agents/README.md)，无需手动输入 Skill 名称。
