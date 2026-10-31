# core_base

Activity、Fragment、ViewModel、Adapter、标题栏、页面状态与弹窗基础封装。

## 定位与依赖

- 分类：架构基础库；来源：AndroidCoreBase。
- 物理目录：`core/architecture/core_base`；Gradle 路径：`:core:core_base`。
- Android AAR；模块声明 minSdk 23，compileSdk 37；JVM target 17。这是模块声明值，实际消费下限还受传递依赖与 Manifest 合并约束。
- 本仓模块依赖：`api :core:lib_xpopup`

## 主要入口与调用流程

[BaseVMActivity](src/main/java/com/wkq/base/activity/BaseVMActivity.kt)、[BaseVMFragment](src/main/java/com/wkq/base/fragment/BaseVMFragment.kt)、[BaseViewModel](src/main/java/com/wkq/base/BaseViewModel.kt)、[BaseRecyclerViewAdapter](src/main/java/com/wkq/base/adapter/BaseRecyclerViewAdapter.kt)、[SystemBarInsets](src/main/java/com/wkq/base/insets/SystemBarInsets.kt)、[DialogKit](src/main/java/com/wkq/base/dialog/DialogKit.kt)。

页面继承合适的 Base → 绑定 XML/ViewBinding 与 ViewModel → 使用现有状态/弹窗组件。窗口 Insets 复用 Base；消费项目检查系统栏、IME、刘海和窗口恢复。

具体业务、主题、权限解释和国际化资源由宿主提供；保留已有基础实现，不把库内历史 VipManager 当成统一付费授权。

## API 37 与窗口策略

- 普通 BaseActivity 保留系统导航，沿用 AndroidX Edge-to-edge 和 SystemBarInsets；顶部安全区包含状态栏、桌面标题栏与刘海。
- BaseTitleActivity 的顶部 owner 仍是 CommonTitleBar，根布局不重复叠加顶部间距；其他三边由原 Base 策略负责。
- BaseFullScreenActivity 隐藏系统栏，允许边缘滑动临时显示；返回前台或弹窗关闭重新获得焦点时恢复当前窗口的全屏策略。
- 全屏默认仅避让刘海及无法隐藏的桌面标题栏，不为临时系统栏挤压内容；输入场景覆盖 shouldApplyImeInset() 返回 true，键盘与底部安全区取最大值。
- 背景需完全铺满的页面可覆盖 applyDefaultSystemBarsInsets()，自行将安全区应用到可操作前景，保证唯一 owner。窗口属于各自 Activity，离开全屏 Activity 不修改其他页面窗口。
- API 35 Robolectric 回归不等于 API 37 设备验收，实际系统栏、低版本、旋转、键盘及大屏行为仍需宿主验证。

## 接入

消费模块 `dependencies` 中添加：

```groovy
implementation project(':core:core_base')
```

## 产物发布与本地检查

标准 Maven 坐标：`com.wkq.dev:core-base:1.3.1`。本库版本来自模块 `version.properties` 的 `VERSION_NAME`；发布配置同步项目版本、POM 与 Gradle 元数据。不要同时依赖同库源码模块与旧 Maven AAR/JAR。

以下命令在 DevLib 根目录执行：

```powershell
.\gradlew.bat :core:core_base:assembleDebug :core:core_base:testDebugUnitTest
.\gradlew.bat :core:core_base:publishReleasePublicationToDevLibLocalRepository
```

本地仓库位于根 `build/repo`。有本仓模块依赖时，先运行根 `publishLibrariesToLocalRepository` 发布完整依赖和插件 marker；不要只复制一个 AAR/JAR。消费工程添加对应文件 Maven 仓库，再按标准坐标接入。

## 工作台与验证状态

实际调用示例：[BaseActivity](../../../feature/feature_demo_foundation/src/main/java/com/wkq/dev/demo/foundation/BaseActivity.kt)。入口由工作台对应分类进入；演示适配不属于本库依赖。

来源快照见 [import-manifest](../../../docs/import-manifest.json)；当前分类见 [library-catalog](../../../docs/library-catalog.json)；逐项验证及未验证边界见 [固定测试报告](../../../doc/testing/TEST_REPORT.md)。本页命令是复核入口，不代表本轮已重新执行。源码迁入、构建和 JVM 测试不等于真机、服务器、视觉或生产验收。

[返回项目说明](../../../README.md) · [库目录](../../README.md)

## 1.3.1 更新说明

- 沿原仓库最新标签版本 `1.3.0` 将补丁号加 1；全屏窗口焦点恢复，系统栏、桌面标题栏及键盘安全区处理。
- 保留公开包名及 API；使用 DevLib 坐标 `com.wkq.dev:core-base:1.3.1`，不代表原仓库 `com.github...` 坐标或标签已发布。
- 发布前核对 AAR、sources、POM 与 module metadata；构建和本地发布不代表设备、视觉或远端发布验收。
- 协作规范见根 [AGENTS.md](../../../AGENTS.md) 与 [.agents 使用说明](../../../.agents/README.md)，无需手动输入 Skill 名称。
