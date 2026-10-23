# DevLib 开发协作规范

## 项目定位与事实

DevLib 是 Android 多模块独立功能库主仓库，模块统一放在 `core/`，演示 App 包名为 `com.wkq.dev`。库包含基础框架、登录支付、文件传输、元数据、图片、文档及媒体适配能力；迁入不等于设备或生产验收通过。
`app/` 与 `feature/` 承载演示和宿主适配，业务产品页面、商品 ID、计费门禁和账号体系由消费项目维护。
项目事实见 `.agents/config/project-profile.yml`，来源见 `docs/import-manifest.json`，模块目录见 `README.md`。

## 加载与适用范围

1. 所有任务读取 `.agents/rules/execution.md`，先确认目标目录、实际技术栈和影响范围。
2. 修改独立库时运行受影响模块编译与测试；修改 App 集成时验证演示应用构建。工具与文档任务按影响范围验证。
3. Android 任务才按下表读取对应工作流及其声明的规则和技能。XML、MVVM、WKQ 等约定仅用于匹配的 Android 目标，不作为所有项目的全局要求。
4. 其他技术栈以目标项目的实际源码、构建配置和局部规范为准；没有对应工作流时按通用执行流程完成，不套用 Android 约束。
5. DropLAN 相关文件是保留的历史专项参考，仅在用户明确要求 DropLAN 任务时加载，不代表 DevLib 的产品需求或当前模块。
6. 用户明确要求优先于仓库默认约定；子目录规范限定在其目录内。索引负责导航，工作流组合规则，技能提供具体方法，避免重复维护冲突规则。

## 任务路由

| 任务 | 工作流或入口 |
| --- | --- |
| 开发库工具、文档、通用 Bug 修复 | `.agents/rules/execution.md` |
| Android 工程生成 | `.agents/workflows/create-project.md` |
| Android 模块与共享能力 | `.agents/workflows/change-architecture.md` |
| Android 页面 | `.agents/workflows/implement-page.md` |
| Android 国际化 | `.agents/workflows/localize-content.md` |
| Android 构建与发布 | `.agents/workflows/change-build.md` |
| Android Bug 修复 | `.agents/workflows/fix-bug.md` |
| Figma 分析 / 实现 | `.agents/workflows/figma-process.md` / `.agents/workflows/figma-code.md` |
| Android 功能回归 | `.agents/skills/android-functional-test-workflow/SKILL.md` |
| Zeplin Android 还原 | `.agents/skills/zeplin-android-layer-implementation/SKILL.md` |
| Google Play 素材 | `.agents/workflows/prepare-play-assets.md` |
| 明确指定的 DropLAN 历史专项 | `.agents/workflows/audit-droplan.md` |

## 维护与验证

- 本仓库已采用项目同根模式；新增库放在 core，演示适配放在 app/feature。生成器自测只使用临时目录，不重生成覆盖已迁入源码。
- 优先复用已有能力；新增领域能力同时维护索引、适用范围和必要的验证入口。修改技能时遵循技能维护规范。
- 不写入真实密钥、证书密码、令牌或本地机器路径；不把旧产品标识直接用于新项目。
- 入口、配置、路由或引用变更后运行 `python -B .agents/scripts/validate_agent_structure.py --project-root .`。
- 校验脚本变更后运行其对应测试；Android 构建和设备验证仅对实际 Android 目标执行。
- 中文汇报修改内容、验证结果和未验证项；结构校验通过不等同于产品构建或功能通过。

## 独立库边界

- core 不依赖 app/feature；每个正式库独立声明依赖、consumer rules、来源与接入说明。
- 保留迁入库公开包名，避免无意义的全量 API 改名；演示应用使用 com.wkq.dev。
- 优先使用本仓库源码模块，不同时依赖相同库的旧 Maven AAR。库产物坐标为 com.wkq.dev:<模块名中下划线替换为连字符>:<版本>。
- 迁入快照以来源清单为准，原仓库保持原样；未完成宿主回接的能力不得标记为迁移闭环完成。
- 付费策略、凭据、品牌、后台地址和权限 UI 由宿主提供。不得把原 App 的 Pro 判断写入底层引擎。
