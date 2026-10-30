# 实现或修改 Android 页面

## 加载

规则：

- `.agents/rules/execution.md`
- `.agents/rules/android.md`
- `.agents/rules/architecture.md`
- `.agents/rules/ui.md`
- `.agents/rules/i18n.md`

Skills：

- `$android-project-architecture-workflow`
- `$android-ui-workflow`
- `$android-i18n-workflow`
- 新增页面设计、页面重构、视觉优化或 UI 审查时加载项目内 `$apple-design`；只做翻译或不影响视觉的行为修复按实际影响选择。
- 涉及依赖、theme 或 manifest 时加载 `$android-build-workflow`

## 功能开发的执行约束

- 通用功能任务先由 `.agents/workflows/develop-feature.md` 判断影响；本工作流负责实际页面实现，纯引擎任务无需强制加载页面步骤。
- Base、XML/ViewBinding/MVVM、国际化和屏幕/系统适配要求分别由已加载规则维护；类与方法中文 KDoc/JavaDoc 遵循 `android.md`，包含新增及修改的 private/override 方法。
- 实现前列明复用的 Base 类型、组件、资源模块及适配范围；实现后逐项自查，汇报用到的 Skills、验证命令和未执行项。
- 聚合静态校验应覆盖 settings 注册的实际 app/feature 业务模块；不扫描 build、生成源码或未启用历史模块。注释与业务职责仍需代码审查，不能用零警告代替。

## 流程

1. 检查目标模块、相邻页面、Base、Adapter、组件和资源体系。
   涉及设计时先按 apple-design 明确任务流程、层级、颜色、字体与状态；Android 使用其可迁移原则，原生组件和项目规则优先，不照搬 iOS 平台约定。
2. 确认页面结构、状态归属、列表/Tab/弹窗方案和 Insets owner；按 UI Skill 的 Android 17 系统栏参考落实边到边/全屏沉浸式选择、窗口策略及退出恢复。
3. 复用已有能力并实现 XML、页面类、ViewModel、Adapter 和资源。
4. 检查文本溢出、滚动、系统栏、小屏幕和生命周期场景。
5. 运行 UI、i18n 和受影响模块最小编译验证。
6. 汇报页面结构、复用项、资源、验证结果和未验证项。
