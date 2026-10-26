# Android 功能开发与优化

## 加载与共同基线

始终读取 `.agents/rules/execution.md`、`.agents/rules/android.md`、`.agents/rules/architecture.md` 和目标项目 `.agents/config/project-profile.yml`。使用 `$android-project-architecture-workflow` 检查已有 Base、公共能力和模块归属；禁止借优化任务重写未授权的基础库。

新增/修改的类型与方法写中文 KDoc/JavaDoc，保持英文名称及公开 API。适用的 Base 继承、MVVM、资源化和屏幕/系统适配必须落实，不适用项需说明原因。

## 按实际影响加载

| 影响 | 继续加载 |
| --- | --- |
| Activity/Fragment、列表、弹窗或布局 | `.agents/workflows/implement-page.md`：UI、架构、国际化 Skills |
| 可见文案、语言目录、日期/数量/RTL | `.agents/workflows/localize-content.md` |
| 模块、网络、文件、权限等共享能力 | `.agents/workflows/change-architecture.md` |
| 依赖、Manifest、SDK、打包或发布 | `.agents/workflows/change-build.md` |
| 功能回归与平台行为变化 | `.agents/skills/android-functional-test-workflow/SKILL.md` |

纯引擎任务不要求人为增加 ViewModel、布局或语言资源。Figma/Zeplin 的实现阶段同样遵守本基线，设计来源不改变 Base 和资源要求。

## 流程与验收

1. 确认任务改动范围、现有工作区修改和复用项，按上述路由加载适用能力。
2. 实现后审查中文注释、Base/组件继承、业务状态归属、可见文案、生命周期和适配；不机械创建空架构层。
3. 对本次修改的 Kotlin/Java/XML 运行 `.agents/scripts/validate_android_source.py`（按其 `--help` 选择文件或 Git 基线）；对源代码层次和动态文案做人工复核。若使用 HEAD 基线，区分之前未提交的修改，不因历史缺口扩大本轮业务范围。
4. 运行聚合静态校验及受影响的编译/测试；缺译按具体 key 记录 fallback 原因和复查条件，不以零退出码代替全语言验收。
5. 先读取用户排除项再决定设备检查。汇报使用的 Skills、复用能力、实际扫描范围、通过/失败/未执行项；不自动提交、推送或创建 CI。
