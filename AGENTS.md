# DevLib 开发协作规范

## 项目定位与事实

DevLib 是 Android 多模块独立功能库主仓库，模块统一放在 `core/`，开发者工作台 App 包名为 `com.wkq.dev`。库包含基础框架、文件传输、元数据、轻量图片与文档导出能力；迁入不等于设备或生产验收通过。
`app/` 与 `feature/` 承载开发者工作台、能力体验和宿主适配，业务产品页面、商品 ID、计费门禁和账号体系由消费项目维护。
项目事实见 `.agents/config/project-profile.yml`，来源见 `docs/import-manifest.json`，模块目录见 `README.md`。

## 加载与适用范围

1. 所有任务读取 `.agents/rules/execution.md`，先确认目标目录、实际技术栈和影响范围。
2. 修改独立库时运行受影响模块编译与测试；修改 App 集成时验证演示应用构建。工具与文档任务按影响范围验证。
3. Android 任务才按下表读取对应工作流及其声明的规则和技能。XML、MVVM、WKQ 等约定仅用于匹配的 Android 目标，不作为所有项目的全局要求。
4. 其他技术栈以目标项目的实际源码、构建配置和局部规范为准；没有对应工作流时按通用执行流程完成，不套用 Android 约束。
5. DropLAN 相关文件是保留的历史专项参考，仅在用户明确要求 DropLAN 任务时加载，不代表 DevLib 的产品需求或当前模块。
6. 同名 Skill 优先读取当前项目 `.agents/skills/<name>/SKILL.md`，不要混用个人目录中的旧副本；缺失时再按任务寻找可用能力。
7. 用户明确要求优先于仓库默认约定；子目录规范限定在其目录内。索引负责导航，工作流组合规则，技能提供具体方法，避免重复维护冲突规则。

## Android 功能开发必读要求

- 新增、修改或优化 Android 功能时，必须先读取 `.agents/workflows/develop-feature.md`；Base 复用、中文注释和适用性检查为共同基线，页面任务加载 UI/国际化 Skills，纯引擎任务按影响选择领域 Skills并说明不适用项。
- Base 继承与组件复用、XML + ViewBinding + MVVM、国际化、屏幕和系统适配不得作为可选建议。具体规则以 `.agents/rules/architecture.md`、`ui.md`、`i18n.md`、`android.md` 为准；未经确认不另起一套基础封装。
- 新增及修改的类、接口、具名对象和方法/函数必须有中文 KDoc/JavaDoc；名称保持规范英文，不改公开 API。既有基础库和未改动声明不因补注释而批量重写。
- 完成时说明所用 Skills、Base/组件复用、文案与适配检查，以及未验证项。脚本通过不等于人工审查、设备或视觉验收完成。

## 任务路由

| 任务 | 工作流或入口 |
| --- | --- |
| 开发库工具、文档、通用 Bug 修复 | `.agents/rules/execution.md` |
| 根据项目代码与资源创建官网 | `.agents/skills/project-official-website/SKILL.md` |
| Android 工程生成 | `.agents/workflows/create-project.md` |
| Android 模块与共享能力 | `.agents/workflows/change-architecture.md` |
| Android 功能开发与优化（统一入口） | `.agents/workflows/develop-feature.md` |
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
- 执行设备命令前先读取项目档案 executionPolicy 与测试计划的排除项；本项目按用户要求不执行真机/adb 操作，后续明确要求可以覆盖该偏好。
- Skill 验证默认在本地执行；CI 创建授权、触发方式和可追溯业务例外遵循 `.agents/rules/execution.md` 的“Skill 验证与 CI 管理边界”。
- 中文汇报修改内容、验证结果和未验证项；结构校验通过不等同于产品构建或功能通过。

## 独立库边界

- core 不依赖 app/feature；每个正式库独立声明依赖、consumer rules、来源与接入说明。
- 不引入 Starxen、BeautySDK 或 DevHub 来源模块；来源排除记录只用于历史追溯。
- 基础库为 AndroidCoreBase、AndroidCoreUtils、AndroidCoreNetwork、AndroidCoreRouters（四个模块）及 XPopup；其他模块按功能能力或 SDK 适配分类，不能仅凭 core_ 前缀认定为基础库。
- 保留迁入库公开包名，避免无意义的全量 API 改名；演示应用使用 com.wkq.dev。
- 优先使用本仓库源码模块，不同时依赖相同库的旧 Maven AAR。库产物坐标为 com.wkq.dev:<模块名中下划线替换为连字符>:<版本>。
- 迁入快照以来源清单为准，原仓库保持原样；未完成宿主回接的能力不得标记为迁移闭环完成。
- 付费策略、凭据、品牌、后台地址和权限 UI 由宿主提供。不得把原 App 的 Pro 判断写入底层引擎。

## 已确认的功能范围

当前为 8 个基础模块、10 个功能库、1 个内部支撑库，共 19 个模块；事实清单见 docs/library-catalog.json。基础库保持原有实现。未经明确需求，不重新引入已移出的分散登录、支付权益库、拍照定位、原产品网页、报告模板、Nearby、证件照、人像、RAW 或模型配送能力。lib_operations 只作为内部支撑，不要求单独业务页面。

Google 登录、Firebase Auth 与 Play Billing 经用户确认统一使用 core_google；旧 Credentials/Firebase/Billing/Entitlement 分散库保持移出，不再并行维护。core_google 不包含广告、Analytics、DevHub 或宿主商品及 Pro 策略。
