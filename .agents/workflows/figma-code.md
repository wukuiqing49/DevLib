# Figma 页面实现

## 加载

规则：

- `.agents/rules/execution.md`
- `.agents/rules/android.md`
- `.agents/rules/architecture.md`
- `.agents/rules/ui.md`
- `.agents/rules/i18n.md`
- `.agents/rules/build.md`
- `.agents/rules/figma.md`

Skills：

- `$android-figma-workflow`
- `$android-project-architecture-workflow`
- `$android-ui-workflow`
- `$android-i18n-workflow`
- `$android-build-workflow`
- `$ui-design`：仅使用设计稿还原与视觉验收分支，复用现有任务卡。
- `$ui-style-review`：核对指定稿与项目规范差异，不以统一风格为由静默重设计。

Skill references：

- `.agents/skills/android-figma-workflow/references/figma-code.md`

## 流程

1. 校验页面任务卡、截图、图层、规范化报告和资源索引属于同一 Node ID。
2. 任务卡存在阻塞项时停止，不使用占位资源或静默降级。
3. 检查项目现有 Base、组件、资源以及页面专属旧实现。
4. 按任务卡映射实现布局、页面类、状态、Adapter 和资源。
5. 逐项核对尺寸合同、Insets owner、资源绑定和已知视觉差异。
6. 运行 Figma、UI、i18n 和受影响模块 Gradle 验证，按 `.agents/skills/ui-design/references/visual-review.md` 对照同版本设计与实际画面，修复差异并复核；执行策略不允许或证据缺失时明确未验证。
