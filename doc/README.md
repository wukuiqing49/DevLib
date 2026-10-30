# DevLib 文档导航

项目定位、19 个库的逐库入口、源码/产物接入及构建命令统一维护在 [项目 README](../README.md)。本页保留为原文档入口，避免维护两套项目介绍。

## 当前使用与接入

- [库目录](../core/README.md)、[分类与 Demo 对照](library-organization.md)。
- [开发者工作台](developer-workbench.md)、[Demo 说明](test-lab.md)。
- [统一记录 PDF/Excel/Word 导出](record-export.md)。
- [NAS/USB 接入与测试](nas-usb-migration.md)。
- [Google 登录、支付页面、兑换与评分](../core/thirdparty/core_google/README.md)。
- [网络库 Maven/GitHub Packages/JitPack 发布](../core/architecture/core_network/docs/core_network_publish.md)。
- [Android 17 兼容基线](android17-compatibility.md)。

## 验证与维护

- [测试计划](testing/TEST_PLAN.md)、[固定测试报告](testing/TEST_REPORT.md)。
- [逐库可靠性优化](library-optimization.md)、[库清理审计](library-cleanup.md)。
- [Agent/Skill 维护分析](agent-skill-review.md)、[协作规范](../AGENTS.md)。
- [当前模块事实](../docs/library-catalog.json)、[来源与移出记录](../docs/import-manifest.json)。

## 专题分析与历史背景

- [逐库分析](library-analysis.md)、[范围取舍](library-scope-review.md)、[架构分析](architecture-review.md)。
- [去重分析](duplicate-capability-analysis.md)、[页面视觉分析](ui-page-review.md)。
- [稳定性与英文覆盖](stability-and-i18n.md)。

专题分析和历史报告可能记录旧路径、旧模块与当时结果，不能替代当前模块清单或本次验证。各库当前 API、依赖、宿主责任、Demo 与检查命令优先查看自己的 README；未执行的设备、远端服务和生产验收不标记为通过。
