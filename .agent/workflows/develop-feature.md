# Android 功能开发与优化

依据需求文档设计项目、涉及跨模块技术方案或用户明确要求资深 Android 开发／架构角色时，使用 `.agents/skills/senior-android-engineer/SKILL.md` 形成文档与源码对应的方案，再继续本流程；明确的局部修改不强制加载。

## 加载与共同基线

始终读取 `.agents/rules/execution.md`、`.agents/rules/android.md`、`.agents/rules/architecture.md` 和目标项目 `.agents/config/project-profile.yml`。使用 `$android-project-architecture-workflow` 检查已有 Base、公共能力和模块归属；禁止借优化任务重写未授权的基础库。

新增/修改的类型与方法写中文 KDoc/JavaDoc，保持英文名称及公开 API。适用的 Base 继承、MVVM、资源化和屏幕/系统适配必须落实，不适用项需说明原因。

## 按实际影响加载

| 影响 | 继续加载 |
| --- | --- |
| Activity/Fragment、列表、弹窗或布局 | `.agents/workflows/implement-page.md`：UI、架构、国际化；设计由 ui-design 主导，交互选型与恢复按该工作流加载 android-interaction-design，复用同一交接 |
| 可见文案、语言目录、日期/数量/RTL | `.agents/workflows/localize-content.md` |
| 模块、网络、文件、权限等共享能力 | `.agents/workflows/change-architecture.md` |
| 依赖、Manifest、SDK、打包或发布 | `.agents/workflows/change-build.md` |
| 功能回归与平台行为变化 | `.agents/skills/android-functional-test-workflow/SKILL.md` |

纯引擎任务不要求人为增加 ViewModel、布局或语言资源。Figma/Zeplin 的实现阶段同样遵守本基线，设计来源不改变 Base 和资源要求。

## 流程与验收

按 execution 的“需求与结果交接”选择澄清与旅程审查。模糊功能请求使用 $clarify-requirements 形成简短任务说明；涉及关键操作或接入流程时使用 $review-user-journey。需求分析明确目标和验收，开发复用已有架构能力，页面设计复用 ui-design；主执行者整合取舍并完成实现。明确的局部修复无需完整角色流程。

1. 确认任务改动范围、现有工作区修改和复用项，按上述路由加载适用能力。
   非平凡功能、跨模块或复杂修复加载 $engineering-review，先核对复用、状态与关键取舍，确定风险对应的验证方式。
2. 实现后审查中文注释、Base/组件继承、业务状态归属、可见文案、生命周期和适配；不机械创建空架构层。
   工程评审适用时跟踪实际调用链与消费者，修复范围内问题；之后的证据应对应修复后的代码。
3. 对本次修改的 Kotlin/Java/XML 运行 `.agents/scripts/validate_android_source.py`（按其 `--help` 选择文件或 Git 基线）；对源代码层次和动态文案做人工复核。若使用 HEAD 基线，区分之前未提交的修改，不因历史缺口扩大本轮业务范围。
4. 运行聚合静态校验及受影响的编译/测试；缺译按具体 key 记录 fallback 原因和复查条件，不以零退出码代替全语言验收。
5. 先读取用户排除项再决定设备检查。汇报使用的 Skills、复用能力、实际扫描范围、通过/失败/未执行项；不自动提交、推送或创建 CI。
6. 已选择旅程审查的任务，对照最初验收条件检查实际产物，修复本轮范围内的问题并复核；源码检查、运行证据与用户体验推断分别说明，不因构建成功宣称流程或视觉验收通过。
7. 功能交付与复杂修复使用 $acceptance-review，将需求与实现、工程评审、测试、旅程和适用的视觉证据对应；复用测试报告细分状态，缺证据明确验收未完成，不重复执行仍有效的验证。
