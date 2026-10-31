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

## 网络库维护

源码位于 `core/architecture/core_network`，版本与发布入口见 [网络库说明](../core/architecture/core_network/README.md) 及 [发布指南](../core/architecture/core_network/docs/core_network_publish.md)。网络库当前沿用根 `devlibVersion`，发布覆盖遵循指南优先级。本轮不升级网络库或路由库。修改版本时同步 README、发布指南和消费示例，核对生成 POM、Gradle 元数据及本地 Maven 产物。

纯网络引擎不强制 UI 设计；涉及 Demo 页面才加载页面和国际化流程。当前设备策略以项目档案 executionPolicy 为准，不因测试自动运行 adb；不把本地发布、Git push 和远端 Maven/JitPack 上线混为一谈。真实凭据不写入项目文件。

## 独立库版本维护

Base、Utils、XPopup 使用各模块 `version.properties` 的 `VERSION_NAME`；通用发布脚本统一读取，缺省模块沿用根 devlibVersion。本轮补丁版本分别为 1.3.1、1.1.2、3.3.2，基于原仓库远端最新标签加 1；Utils 本地历史配置低于远端标签时，以已发布标签为基准。

兼容性修复通常仅递增补丁号，新增兼容功能或破坏性变更另行评估。只给实际有变更且需要发布的库升级；同步模块 README、根 README 和消费示例，验证 POM/Gradle 元数据及传递依赖版本。原仓库源码、标签和 com.github 坐标不随 DevLib 的 com.wkq.dev 坐标自动更新，不把 Git push 当作 Maven 上线。
