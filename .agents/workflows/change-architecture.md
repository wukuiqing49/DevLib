# 调整 Android 项目架构

## 加载

规则：

- `.agents/rules/execution.md`
- `.agents/rules/android.md`
- `.agents/rules/architecture.md`
- `.agents/rules/build.md`
- DevLib 模块或目录调整额外读取 `.agents/rules/library-organization.md`；其他目标按自身项目规则。
- 仅在用户明确指定 DropLAN 专项时读取 `.agents/rules/droplan.md`，首次 P0 设计按 `.agents/workflows/audit-droplan.md` 执行。

Skills：

- `$android-project-architecture-workflow`
- `$android-build-workflow`

## 流程

1. 读取项目档案、settings、版本目录、相关模块构建文件和相邻代码。
2. 判断目标能力是否已有实现或可由 WKQ/现有共享层复用。
3. 明确功能专属逻辑、共享逻辑和平台配置的归属。
4. 设计最小模块或依赖变更，不改变无关外部接口。
5. 同步模块清单、物理目录映射、项目档案、接入文档和 Demo 分类；运行架构门禁、目录测试、依赖解析和受影响模块编译。
6. 汇报模块归属、依赖方向、复用选择和验证结果。
