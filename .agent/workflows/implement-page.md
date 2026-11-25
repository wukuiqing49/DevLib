# 实现或修改 Android 页面

## 加载

规则：

- `.agents/rules/execution.md`
- `.agents/rules/android.md`
- `.agents/rules/architecture.md`
- `.agents/rules/ui.md`
- `.agents/rules/ui-style.md`：设计与视觉任务读取；消费项目使用其自身规范。
- `.agents/rules/i18n.md`

Skills：

- `$android-project-architecture-workflow`
- `$android-ui-workflow`
- `$android-i18n-workflow`
- 新增页面设计、页面重构、视觉优化或 UI 审查时加载项目内 `$ui-design`；只做翻译或不影响视觉的行为修复按实际影响选择。
- `$ui-style-review`：视觉任务消费同一交接检查风格，不另做方案。
- `$android-interaction-design`：新页面交互未定、多步骤流程、导航/弹层/返回变化、UX 重构或设计稿平台交互映射时加载；已有完整方案只补缺口。局部样式/文案和纯引擎任务不触发，状态数量不作为门槛。
- 涉及依赖、theme 或 manifest 时加载 `$android-build-workflow`
- 目标或成功标准模糊时加载 `$clarify-requirements`；新增、重构关键操作流程、体验优化或明确要求用户视角审查时加载 `$review-user-journey`，复用上游已形成的任务说明。

## 功能开发的执行约束

- 通用功能任务先由 `.agents/workflows/develop-feature.md` 判断影响；本工作流负责实际页面实现，纯引擎任务无需强制加载页面步骤。
- Base、XML/ViewBinding/MVVM、国际化和屏幕/系统适配要求分别由已加载规则维护；类与方法中文 KDoc/JavaDoc 遵循 `android.md`，包含新增及修改的 private/override 方法。
- 实现前列明复用的 Base 类型、组件、资源模块及适配范围；实现后逐项自查，汇报用到的 Skills、验证命令和未执行项。
- 聚合静态校验应覆盖 settings 注册的实际 app/feature 业务模块；不扫描 build、生成源码或未启用历史模块。注释与业务职责仍需代码审查，不能用零警告代替。

## 流程

1. 检查目标模块、相邻页面、Base、Adapter、组件和资源体系。
   复用架构 Skill 的基础能力映射，核实实际类型/API/调用位置；按架构规则记录导航选型和已有契约。
   先确定具体用户、主操作及可观察验收条件；已有明确需求直接复用。旅程审查适用时，先检查入口、反馈、完成及本轮涉及的失败恢复路径。
   涉及设计时按 ui-design 选择设计稿还原、已有页面改进或从零设计，形成可消费的设计说明；已有任务卡直接补充必要项。DevLib 工作台读取 `.agents/config/ui-baseline.md`，外部项目使用其自身基线。
2. 设计交接核对本轮功能入口映射、主路径、关键状态、资源及风格例外；复杂重构补关键页面线框，局部修复保留紧凑说明。先做方案自查再实现，不新增逐页审批。
   交互专项适用时补充容器、导航/参数/结果、关闭与返回、输入/任务恢复、防重复提交及反馈，并映射现有基础组件；合入同一交接，缺口记录最小方案，避免重新封装 Base。
   确认页面结构、状态归属、列表/Tab/弹窗方案和 Insets owner；按 UI Skill 的 Android 17 系统栏参考落实边到边/全屏沉浸式选择、窗口策略及退出恢复。
3. 复用已有能力并实现 XML、页面类、ViewModel、Adapter 和资源。
4. 检查文本溢出、滚动、系统栏、小屏幕和生命周期场景。
5. 运行 UI、i18n 和受影响模块最小编译验证。
   按 `.agents/skills/ui-design/references/visual-review.md` 获取允许的实际画面证据，对照设计说明记录差异，修复范围内问题并复核；没有运行/渲染条件时明确未验证，不绕过设备排除项。
6. 汇报页面结构、复用项、资源、验证结果和未验证项。
   旅程审查适用时先对照实际产物复核并修复范围内问题；缺少运行或截图证据的验收项明确标为未验证，不以角色自评替代。
   功能交付按 execution 使用 $acceptance-review 汇总需求条件与证据；复杂状态或跨模块变更使用 $engineering-review 检查实现。复用上游已完成的评审，简单布局修复不强制完整流程。
