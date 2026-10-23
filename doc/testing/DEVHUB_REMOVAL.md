> 历史记录：当前来源范围已收紧，Starxen、BeautySDK 和 DevHub 均移出。旧数量、停用开关和测试结果不代表当前构建；以项目 README 和最新测试报告为准。

# DevHub 移除与当前范围验证

日期：2026-10-23；基于 76725ec 的未提交工作区，包含此前美颜停用调整。

## 修改

- 移除 core/lib_devhub 的受版本管理源码、模块注册及闲置版本声明。
- 保留独立 DevHub 仓库；来源清单 removedModules 和历史测试证据记录原迁入状态。
- 五个美颜模块保留源码，默认停用。
- 当前分析及优先级见 [架构复核](../architecture-review.md)。

## 已验证

- Agent 结构校验通过。
- Android 构建配置校验通过，0 条警告。
- 默认 projects 注册 39 个 core 模块，未包含 DevHub 和五个美颜模块。
- 启用美颜的 projects 注册 44 个 core 模块，未包含 DevHub。
- 当前源码、构建配置与依赖目录中没有 DevHub 依赖；来源记录和历史报告中的名称是历史说明。
- 两份结构化 JSON 解析通过。
- 默认库构建、App debug 打包及聚合 JVM 测试全部通过：BUILD SUCCESSFUL，耗时 9 分 25 秒，1228 项 Gradle 任务。
- 当前默认范围共 617 项 JVM 用例：616 通过、0 失败、0 错误、1 项既有跳过。按本次 projects 模块范围汇总 XML，排除 DevHub 与美颜旧缓存；[逐模块结果](evidence/2026-10-23-scope/summary.json)。

执行命令（仓库根目录，JDK 17）：

```powershell
python .agents/scripts/validate_agent_structure.py --project-root .
python .agents/skills/android-build-workflow/scripts/validate_build_output.py --project-root .
.\gradlew.bat projects assembleLibraries :app:assembleDebug testLibraries --offline --console=plain
.\gradlew.bat "-Pdevlib.enableBeauty=true" projects --offline --console=plain
```

## 验证边界

本轮不重新验证手机 UI、第三方登录/购买、双设备 Nearby、服务器上传及 Play 模型配送，不触发真机主动崩溃。旧 785 项统计属于包含 DevHub 及美颜的迁入历史；当前范围结果单独记录，不覆盖历史证据。
