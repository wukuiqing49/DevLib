# DevLib Agent 能力索引

本文件只提供导航。运行时加载和冲突优先级以根目录 `AGENTS.md` 为准。

## 分层

| 层级 | 目录 | 职责 |
| --- | --- | --- |
| 入口 | `AGENTS.md` | 自动加载、项目事实、任务路由和全局红线 |
| 配置 | `.agents/config/` | 项目技术栈档案和脚手架固定参数 |
| 规则 | `.agents/rules/` | 稳定约束，说明必须/禁止什么 |
| 流程 | `.agents/workflows/` | 任务步骤，组合 Rules 和 Skills |
| 能力 | `.agents/skills/` | 领域实现方法、脚本、references 和 assets |
| 输入 | `.agents/prompts/` | 用户可填写的任务模板，不承载规则 |
| 任务数据 | `.ai-work/` | Figma、商店素材等输入、中间产物和结果 |

## 配置

- `config/project-profile.yml`：DevLib Android 多模块库与演示工程事实，当前为 `initialized / integration-validation`。
- `config/project-scaffold.yml`：当前 DevLib 生成基线，名称 `DevLib`、包名 `com.wkq.dev`、Application 类 `DevLibApplication`。已迁入的 core 源码不通过重新生成覆盖。
- 根目录 `README.md` 是功能模块与接入目录；`docs/import-manifest.json` 记录来源和快照摘要；`doc/testing/TEST_REPORT.md` 记录实际验证。
- `agentFiles` 注册的 DropLAN 历史产品专项文件不自动启用；通用库边界以当前 AGENTS.md 和模块构建配置为准。

## Rules

- `rules/android.md`：Android 通用修改边界、生命周期、性能、安全和兼容性。
- `rules/architecture.md`：模块边界、共享能力和 WKQ 复用。
- `rules/ui.md`：页面结构、滚动、文字、资源和 Insets。
- `rules/i18n.md`：strings、多语言、占位符、plurals 和 raw HTML。
- `rules/build.md`：Gradle、版本、依赖、签名、manifest、release 和 native。
- `rules/figma.md`：Figma 阶段门禁、图层映射、屏幕适配和验收。
- `rules/play-assets.md`：Google Play 素材保真、安全与合规。
- `rules/execution.md`：通用执行、Bug 修复、验证和输出。
- `rules/droplan.md`：多端免安装、离线热点/LAN、大文件、授权、任务队列、最快可用推荐与真机验收。

## Workflows

- `workflows/create-project.md`：创建或调整 Android 工程。
- `workflows/audit-droplan.md`：审计实际状态，规划或实现 P0，分别验证跨端大文件、连接与速度推荐。
- `workflows/change-architecture.md`：调整模块或共享能力。
- `workflows/develop-feature.md`：共同开发基线和按影响加载，包含中文注释与增量源码检查。
- `workflows/implement-page.md`：实现或修改 Android 页面。
- `workflows/localize-content.md`：修改文案和多语言。
- `workflows/change-build.md`：修改构建与发布配置。
- `workflows/fix-bug.md`：诊断并修复 Android Bug。
- `workflows/figma-process.md`：Figma 分析阶段。
- `workflows/figma-code.md`：Figma 实现阶段。
- `workflows/prepare-play-assets.md`：准备 Google Play 商店素材。

## Skills

- `skills/google-play-aso/`：默认核对 Skill 所属项目代码和发行包，使用内置 Python 数据引擎，逐 App 输出数据分析与 ASO 优化方案；支持 .agents/.agent，不依赖外部工具工程或网页，不复制凭据。
- `skills/project-official-website/`：从实际项目代码、文档和真实资源提取产品事实，创建官网并执行本地验证；包含事实/文件归属记录、静态校验脚本和回归用例，不自动部署或创建 CI。

- `skills/android-functional-test-workflow/`：跨项目的改动影响分析、增量/发布回归与固定测试报告，默认输出 `doc/testing/TEST_REPORT.md`。
- `skills/android-project-scaffold-workflow/`：完整项目生成器、模板和生成测试。
- `skills/android-project-architecture-workflow/`：模块归属、共享能力分析和架构门禁。
- `skills/android-ui-workflow/`：XML UI 实现和 UI 静态门禁。
- `skills/android-i18n-workflow/`：国际化资源处理和 i18n 门禁。
- `skills/android-build-workflow/`：Gradle/发布修改和构建门禁。
- `skills/android-figma-workflow/`：Figma 拉取、规范化、映射、模板和验收门禁。
- `skills/plan-google-play-listing-assets/`：共享产品事实、Seed Keywords 和定位，统一策划 Feature Graphic、商店截图与 Preview Video，并导出独立 Prompt 文件。
- `skills/zeplin-android-layer-implementation/`：读取 Zeplin 图层、资源和设计 Token，并实现 Android XML + ViewBinding 页面。

## Prompts

- `prompts/create-project.md`
- `prompts/audit-droplan.md`
- `prompts/change-architecture.md`
- `prompts/implement-page.md`
- `prompts/localize-content.md`
- `prompts/change-build.md`
- `prompts/fix-bug.md`
- `prompts/figma-page.md`
- `prompts/prepare-play-assets.md`

## 验证

- `scripts/validate_agent_structure.py`：检查根入口、配置、通用/项目注册文件、Rules、Workflows、Prompts、Skills 和引用关系。
- `scripts/validate_play_assets.py`：检查 Google Play PNG/JPEG 素材的尺寸、比例和透明像素。
- `skills/plan-google-play-listing-assets/scripts/`：初始化素材工作区，导出独立 Prompt 文件，并校验关键词输入、Seed CSV、共享策略和三类素材 Brief。
- `scripts/validate_android_workflows.py`：先检查 Agent 结构，再聚合验证目标 Android 工程的 build、architecture、UI、i18n 和已有 Figma 产物。

开发库维护时运行：

```powershell
python .agents/scripts/validate_agent_structure.py --project-root .
python .agents/scripts/validate_android_workflows.py --project-root . --config-only
```

结构检查默认 `--mode auto`，`library` 开发库和未初始化目标只检查配置及已声明路径；存在 Gradle 文件或 lifecycle 为 initialized 时检查工程事实。`--mode project` 强制工程检查，`--mode config` 只检查配置。配置检查通过不代表 Android 构建或功能通过。

新建其他 Android 工程后更新其目标工程档案；DevLib 自身已初始化为 Android 多模块库工程，按根目录真实事实维护。对目标工程执行聚合门禁和实际 Gradle 构建/测试；聚合门禁在缺少 settings 时失败，避免空工程误报通过。

维护生命周期判断或脚手架配置时运行：

```powershell
python .agents/scripts/tests/test_validate_agent_structure.py -v
python .agents/skills/android-project-scaffold-workflow/scripts/test_render_project_scaffold.py -v
```

Android 17 兼容规则：`skills/android-build-workflow/references/android-17.md`，由构建、脚手架、架构、UI 和测试 Skills 按任务加载。

Android 功能开发统一入口为 `workflows/develop-feature.md`：Base、UI、国际化、屏幕/系统适配及类/方法中文注释为必读要求，纯引擎任务按实际范围说明不适用项。中文注释规则统一维护在 `rules/android.md`，不要求重命名 API 或批量修改基础库。

聚合验证从 settings 的字面量 include 收集实际 feature 模块，结合配置中的 app/featureApp/featureRes 扫描源码和资源；动态 settings 或自定义 projectDir 映射需显式核实并补充目标，不能声称覆盖未解析模块。新增模块收集逻辑后执行：

```powershell
python -B .agents/scripts/tests/test_validate_android_workflows.py -v
```

## 源码增量检查与工具边界

功能任务运行 `python -B .agents/scripts/validate_android_source.py --project-root . --file <本次源码路径>`，多文件重复 `--file`。省略文件时按 `--base-ref HEAD` 收集 app/feature 中已修改及未跟踪的 main Kotlin/Java/XML；检查的是选中文件全文，不是仅新增行，必须区分历史欠账。

检查 DOC001（常见类型/方法的中文 KDoc/JavaDoc）、BASE001（明显直接继承平台组件）、I18N001（常见可见文案硬编码）。这不是 Kotlin/Java 语义分析器：扩展/多行复杂声明、间接继承、Compose、Toast/动态拼接、翻译质量和屏幕适配仍需人工及专项验证。Java 重载、注解或复杂签名的误报需复核，不得机械改业务来通过检查。

`--report <路径>` 输出 JSON 证据。`--exceptions <JSON路径>` 支持具体条目例外：数组内每项必须有 path、rule、line、reason、alternativeValidation、reviewAfter；path 为精确相对路径，禁止通配。保留例外报告，代码移动后重新核对，不能豁免整类规则。

聚合检查分别输出 tool-structure 与 target-profile；外部目标没有 profile 时明确报告事实未验证，不借用 DevLib 配置充当目标事实。模块目录只来自目标配置及 settings 已注册的字面量 include；动态 settings/projectDir 映射仍需人工核实。Figma 不再默认放宽 Adapter/Fragment 约束，也不因底部导航自动要求 ViewPager2。


宿主必需语言由目标 project-profile.yml 的 android.requiredHostLocales 声明（DevLib 为 en），聚合 i18n 检查会验证整个语言目录存在；独立命令可重复传入 `--required-locale en`。string-array 同样检查缺译、条目数量及逐项占位符，技术协议值和数组顺序不得因翻译改变。该约束只扫描宿主模块，不要求改写迁入基础库资源。

## Skill 维护补充（2026-10-27）

- UI 的 Android 17 沉浸式与系统栏方法见 `skills/android-ui-workflow/references/system-bars-android17.md`；Base 复用、窗口恢复与设备排除仍按目标规则执行。
- Figma 分页校验仅由任务卡明确需求触发；已获实现授权时分析完成后继续，局部改动不强制整页重建。
- 国际化验收传入真实资源目录、`--strict-missing` 和目标必需语言；按参数索引比较占位符，限定符资源允许基础语言继承，夜间目录不能代替全天候语言覆盖。
- 工具与目标工程分开定位；宿主共享编排留在 feature，core 只承载可独立接入能力。Play 关键词两阶段协议保留。
