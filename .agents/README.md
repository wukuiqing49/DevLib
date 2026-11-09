# 项目 Agent 使用说明

本目录为 DevLib 项目级开发协作入口，与根 [AGENTS.md](../AGENTS.md) 配合使用。名称为 `.agents/`，不另建 `.agent/` 或 `Agent.md` 副本。

## 使用方式

直接说目标，例如“优化页面层级”“修复下载取消”“升级网络库补丁版本”。无需输入 Skill 名称；Agent 根据任务读取规则、工作流和适用能力，已有需求、设计和有效测试证据直接复用。

- `config/`：项目事实、生成配置与 UI 基线；版本事实仍以真实 Gradle 配置为准。
- `rules/`：技术边界、Base 复用、中文注释、国际化、适配及验证约束。
- `workflows/`：按开发、页面、架构、构建及修复等任务组合执行步骤。
- `skills/`：需求、架构开发、设计、工程评审、用户旅程和验收等具体方法；按影响选择，不要求每项任务启动全部角色。
- `scripts/`：本地结构与配置校验；通过不代表产品功能已验收。

完整导航见 [INDEX.md](INDEX.md)。当前执行者默认按需切换视角，不自动创建多个智能体。

## 推广视频制作与发布

直接描述“分析这个项目，用手机实录推广视频，制作英文封面、简介、话题与字幕并发布到 TikTok 和 YouTube”，会通过根入口路由到 [推广视频工作流](workflows/publish-promo-video.md) 和项目内 `promo-video-publisher`。只要求方案或素材时不发布。

能力支持不同项目、设备及已有成片，不固定产品或账号。完整操作、前置条件、归档和复用步骤见 [推广视频指南](../doc/promo-video-publisher.md)；可填写 [任务模板](prompts/publish-promo-video.md)。DevLib 当前设备默认排除策略保留，用户明确要求本次手机录制/发布时按该次授权执行；移植 Skill 本身不触发设备操作。

## 在其他项目复用

1. 先读取目标项目已有规范、源码与构建配置，确认实际技术栈和需要的能力。
2. 合并需要的 Skills，并保留其引用的 scripts、references、assets；同时合并对应工作流和规则。不要覆盖目标项目已有文件，也不要只复制一个脱离引用资源的 SKILL.md。
3. 按目标工程改写 `AGENTS.md` 与 `config/project-profile.yml`：包名、模块路径、SDK、语言、Base、UI 基线和设备执行策略均使用目标项目事实。DevLib 的库清单、迁入记录、历史专项和产品约束不直接套用。
4. 如果目标项目已有 `CLAUDE.md` 或 `GEMINI.md`，可在其中加入“开始任务前读取 AGENTS.md，并按任务加载 .agents/ 中对应工作流与 Skill”的入口说明，统一维护规则正文。实际是否加载应通过该工具的任务反馈确认。
5. 根据目标需求验证结构与功能：`python -B .agents/scripts/validate_agent_structure.py --project-root <目标目录>`；Android 项目再执行受影响模块的真实构建与测试。结构通过不代表功能验证通过。

第一次使用可以直接描述：

```text
读取本项目规范和真实工程，检查接入的 Skills 是否适用。
修正项目档案中的路径与技术栈，列出可复用的 Base 和公共组件，
再按本次需求选择工作流并执行验证。
```

Skills 提供开发方法，库提供运行时能力，两者按需组合。普通项目沿用现有简单导航；有跨业务模块解耦、统一拦截或服务发现需求的大型项目再评估路由体系。其他技术栈以目标项目规则为准，不套用 Android XML/MVVM 约定。

## 网络库维护

源码位于 `core/architecture/core_network`，版本与发布入口见 [网络库说明](../core/architecture/core_network/README.md) 及 [发布指南](../core/architecture/core_network/docs/core_network_publish.md)。网络库当前沿用根 `devlibVersion`，发布覆盖遵循指南优先级。本轮不升级网络库或路由库。修改版本时同步 README、发布指南和消费示例，核对生成 POM、Gradle 元数据及本地 Maven 产物。

纯网络引擎不强制 UI 设计；涉及 Demo 页面才加载页面和国际化流程。当前设备策略以项目档案 executionPolicy 为准，不因测试自动运行 adb；不把本地发布、Git push 和远端 Maven/JitPack 上线混为一谈。真实凭据不写入项目文件。

## 独立库版本维护

Base、Utils、XPopup 使用各模块 `version.properties` 的 `VERSION_NAME`；通用发布脚本统一读取，缺省模块沿用根 devlibVersion。本轮补丁版本分别为 1.3.1、1.1.2、3.3.2，基于原仓库远端最新标签加 1；Utils 本地历史配置低于远端标签时，以已发布标签为基准。

兼容性修复通常仅递增补丁号，新增兼容功能或破坏性变更另行评估。只给实际有变更且需要发布的库升级；同步模块 README、根 README 和消费示例，验证 POM/Gradle 元数据及传递依赖版本。原仓库源码、标签和 com.github 坐标不随 DevLib 的 com.wkq.dev 坐标自动更新，不把 Git push 当作 Maven 上线。
