---
name: android-ui-workflow
description: "用于实现和验证 Android XML UI：页面、列表、弹窗、资源样式、屏幕适配，以及 Android 17 下的 Edge-to-edge、全屏沉浸式和 WindowInsets。"
---

# Android UI 实现能力

## 项目上下文

以下项目路径均相对目标工程；脚本和 references 相对本 Skill。跨项目调用读取目标规则与事实，缺失时从实际源码/构建核实并说明，不套用 DevLib 的包名、模块或执行策略。脚手架生成参数使用用户选定配置。

在目标项目中读取适用且存在的文件：

- `.agents/config/project-profile.yml`
- `.agents/rules/execution.md`
- `.agents/rules/android.md`
- `.agents/rules/ui.md`
- 涉及文案时读取 `.agents/rules/i18n.md`
- 涉及新依赖、theme 或 manifest 时读取 `.agents/rules/build.md`

## 实现方法

新增页面设计、页面重构、视觉优化或 UI 审查时，先加载项目内 `$apple-design`，按其平台适用性提取设计原则；Android 组件、尺寸、导航与实现遵循本项目规范。仅修正文案翻译或不影响视觉结构的局部行为时按实际影响选择，不强制整页重设计。

1. 读取目标页面、相邻页面、Base 类型、已有组件和资源体系。
2. 判断 Activity/Fragment、ViewModel、Adapter、列表、Tab 和弹窗职责。
3. 明确普通边到边或全屏沉浸式策略、根布局、弹性内容区、固定视觉区及唯一 Insets owner；页面实现读取 [Android 17 系统栏与沉浸式](references/system-bars-android17.md)，检查 Base 已有实现与窗口状态恢复。
4. 复用现有 style、dimen、color、drawable、string 和项目 UI 封装。
5. 使用 XML + ViewBinding 实现页面，保持资源名和外部调用稳定。
6. 检查文字溢出、滚动冲突、字体缩放、小屏幕、系统栏和生命周期。

## 与其他能力协作

- 布局、层级、颜色、字体、反馈和无障碍的设计审查交给 `$apple-design`；设计结果落实为现有 Android 组件与资源，不替代本 Skill 的 Base、国际化和 Android 17 适配验收。
- 文案、plurals 和语言覆盖交给 `$android-i18n-workflow`。
- 模块归属、Base 和公共组件复用交给 `$android-project-architecture-workflow`。
- 新依赖、theme、manifest 和 targetSdk 行为交给 `$android-build-workflow`。
- Figma 尺寸和图层映射由 `$android-figma-workflow` 提供任务卡，本 Skill 负责 Android UI 落地。

## 验证

```powershell
python .agents/skills/android-ui-workflow/scripts/validate_ui_output.py `
  --module-src app/src/main/java --module-src feature/feature_app/src/main/java `
  --module-res app/src/main/res --module-res feature/feature_app/src/main/res `
  --module-res feature/feature_res/src/main/res
```

补充受影响模块编译。列表、分页、弹窗、Insets 或字体任务需要说明手动/设备场景的已验证与未验证范围。

## Android 17 / API 37

涉及 SDK 37、Android 17 迁移、局域网权限或大屏行为时，读取 [Android 17 兼容基线](../android-build-workflow/references/android-17.md)。同步项目与生成配置，按能力选择检查；构建通过不能代替运行验证。

功能实现必须按已加载规则复用 Base；新增及修改的类型与方法写中文 KDoc/JavaDoc。页面同时执行 UI、国际化及屏幕/系统适配检查，完成时说明复用项和未验证项。
