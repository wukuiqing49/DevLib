# 文档验证命令与边界

从 DevLib 根目录运行：

```powershell
python -B .agents/scripts/validate_agent_structure.py --project-root .
python -B scripts/test_core_layout.py -v
python -B scripts/verify_demo_catalog.py --catalog-only
.\gradlew.bat help -I doc/testing/evidence/2026-10-30-library-readmes/task-registration.gradle --no-daemon --console=plain
git diff --check
```

另外通过 Python 逐一读取 23 份当前 README：检查 UTF-8、替换字符、代码围栏闭合、相对链接目标存在；核对 19 个库均具备定位/入口/发布/验证栏目，根 README 覆盖完整 catalog。链接结果及文件 SHA-256 见 summary.json。未检查外部网站实时状态，源码链接不等同 API 全面审计。

Gradle help 后置断言核对 19 个模块的 57 个文档任务已注册，打印 README_TASKS_VERIFIED=57；没有执行这些任务，不将 help 成功表述为 19 库重编译或单测通过。已有 Gradle 弃用提示保留。

本次只更新文档，不改库实现、资源、SDK、依赖或 CI。未执行设备/服务器/远端发布、提交或 push。
