# 创建或调整 Android 工程

依据需求文档设计项目、涉及跨模块技术方案或用户明确要求资深 Android 开发／架构角色时，使用 `.agents/skills/senior-android-engineer/SKILL.md` 形成文档与源码对应的方案，再继续本流程；明确的局部修改不强制加载。

## 加载

规则：

- `.agents/rules/execution.md`
- `.agents/rules/android.md`
- `.agents/rules/architecture.md`
- `.agents/rules/build.md`
- `.agents/rules/ui.md`
- `.agents/rules/i18n.md`
- 仅在用户明确指定 DropLAN 时读取 `.agents/rules/droplan.md`

Skills：

- `$android-project-scaffold-workflow`
- `$android-project-architecture-workflow`
- `$android-build-workflow`
- 涉及真实页面设计时加载项目内 `$ui-design`、`$android-ui-workflow` 和 `$android-i18n-workflow`；仅生成工程骨架不强制设计审查。

配置：

- `.agents/config/project-profile.yml`
- `.agents/config/project-scaffold.yml`

## 流程

1. 明确工作流根目录和 Android 目标根目录；DevLib 根目录已有真实多模块工程，维护本项目时原位最小修改，创建其他应用时使用独立目标目录，禁止把工程生成到 `.agents/` 内部。仅编辑配置时不启动真实工程生成。
2. 判断目标目录是空目录、带 marker 的模板生成工程还是无 marker 的已有工程。
3. 核对脚手架配置、Gradle 事实源、模块边界和现有文件。
   按架构规则确定所需基础能力和导航方案，并记录生成器已支持与需增量实现的部分，不将现有工作台依赖直接复制到新项目。
   包含真实页面时按 ui-design 的从零设计分支形成布局、状态和资源交接；消费产品建立自己的设计基线，不继承 DevLib 工作台品牌。
   页面交互未定或涉及多步骤/导航变化时使用 `$android-interaction-design`，将基础组件映射、返回恢复和反馈合入同一交接；仅生成骨架不加载完整交互设计。
4. 空目录使用完整生成器；带 marker 的工程允许受控 `--force`；无 marker 工程只做最小合并。
5. 对比 marker 的 `generatedDigests`，保护已被业务修改的生成文件和 `preservedPaths`，确认公开应用配置不含签名秘密，debug 不使用 release 签名。
6. 检查模块图、WKQ 接入、版本、签名、网络、FileProvider、资源、RTL 和 Edge-to-edge 基础能力：`application` 必须启用 `android:supportsRtl="true"`；XML 布局、`gravity`、边距、内边距和 compound drawable 优先使用 `start/end` 逻辑方向属性，禁止为普通页面新增 `left/right` 物理方向属性；含返回、前进、抽屉、工具栏操作或方向性图标的页面，必须在阿拉伯语等 RTL Locale 下做视觉验收。
7. 真实项目生成到用户明确指定的目标根目录，包括已确认的项目同根目录；Skill 自测只生成到临时目录或 `.ai-work/scaffold-smoke/`。
8. 从工具根目录运行生成器测试和结构门禁，对目标根目录运行聚合门禁、`projects` 与 `assembleDebug`。
9. 根据实际生成文件更新目标工程的项目档案（不覆盖 DevLib 档案）：将 lifecycle 改为 initialized，填写真实模块、构建事实源和可用验证命令，并更新 AGENTS.md 的项目阶段说明；框架接入不等于业务功能已完成。
10. 汇报生成/修改文件、配置入口、验证结果和未验证项。
    真实页面按 `.agents/skills/ui-design/references/visual-review.md` 对照实际画面并处理差异；只有骨架或缺少渲染证据时不声明视觉验收通过。
