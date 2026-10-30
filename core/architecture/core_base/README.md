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

## 接入

消费模块 `dependencies` 中添加：

```groovy
implementation project(':core:core_base')
```

## 产物发布与本地检查

标准 Maven 坐标：`com.wkq.dev:core-base:0.1.0-SNAPSHOT`。默认版本统一来自根 `devlibVersion`；网络库另支持发布版本覆盖。不要同时依赖同库源码模块与旧 Maven AAR/JAR。

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
