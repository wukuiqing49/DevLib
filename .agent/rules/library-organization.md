# DevLib 库分类与入口维护

仅适用于本仓库，其他项目以其自身结构为准。权威模块清单为 `docs/library-catalog.json`，实际构建注册及目录映射为 `settings.gradle`。

| 分类键 | 物理目录 | 职责 | Demo |
| --- | --- | --- | --- |
| architecture | core/architecture | Base、Utils、Network、Routers、XPopup 架构底座 | 五套体系，路由详情覆盖四个子模块 |
| functional | core/functional | 文件、图片、元数据、二维码、文档等独立能力 | 每库保留独立业务流程 |
| thirdparty | core/thirdparty | Google 等外部平台服务适配 | 单独目录，明确宿主配置与外部验收条件 |
| internal | core/internal | 跨功能库内部契约 | 不建独立业务页面 |

- 分类看职责，不按 `core_` / `lib_` 前缀或来源项目推断；使用第三方依赖不等于三方服务库。XPopup 保留架构归属。
- `category` 与 `directoryGroup` 使用表中分类键；`path` 指向真实模块目录。来源快照只说明迁入来源，不能作为当前目录入口。
- 分类容器不注册为 Gradle 模块；保留 `:core:<module>` 逻辑路径、公开包名及发布坐标，以显式 projectDir 映射定位源码。
- core 不依赖 app/feature；功能库可复用架构与内部支撑，三方适配可复用现有架构页面，但宿主商品、账号、Pro 策略和真实凭据不进入底层库。
- 新增、移除、归类模块时同步清单、settings、project-profile、core/README、项目文档、Demo 目录与对应测试。内部支撑必须指明真实消费库。
- 顶层导航为架构、功能、三方、工作区；分类与功能域路由分开，已有路由和保存状态键不因文案调整而改名。每类的筛选与快捷入口互斥。
- 本仓库只维护 `.agents` 与 `AGENTS.md` 作为规范入口，不创建同内容的 `.agent` 或 `Agent.md` 副本。新工程生成参数与现有项目事实分开，禁止重生成覆盖迁入源码。

本地验证：`python -B scripts/test_core_layout.py`、`python -B scripts/test_verify_demo_catalog.py`、`python -B scripts/verify_demo_catalog.py --catalog-only`。涉及 Demo 时还需 App 构建、目录单元测试与严格 UI/国际化检查；APK 路由检查在构建后执行。测试报告保留历史证据，仅追加本轮实际结果，不写成设备验收完成。
