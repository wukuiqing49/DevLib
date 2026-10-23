> 历史记录：当前来源范围已收紧，Starxen、BeautySDK 和 DevHub 均移出。旧数量、停用开关和测试结果不代表当前构建；以项目 README 和最新测试报告为准。

# 美颜模块停用验证

> 历史范围说明：本记录生成时包含 DevHub；该模块现已移出。美颜开关记录中的旧模块总数也对应移除前状态。当前范围见 [架构分析](../architecture-review.md) 和后续验证记录。

日期：2026-10-23。用户选择暂时停用美颜、保留源码。

## 配置

`devlib.enableBeauty=false` 为默认值。停用 lib_beauty、lib_beauty_mediapipe、lib_beauty_rfb、lib_live_beauty、lib_tencent_beauty_kit；未删除实现、模型或依赖快照。相机、Agora、直播接口继续启用。

## 实测

| 检查 | 结果 |
| --- | --- |
| 默认 projects | 40 个 core 子模块，5 个美颜模块均未注册 |
| 默认 assembleLibraries + :app:assembleDebug | 退出码 0，BUILD SUCCESSFUL（54 秒） |
| 默认构建/测试/发布聚合任务 dry-run | 退出码 0，未包含任何停用模块任务 |
| 开启开关后的 projects | 45 个 core 子模块，5 个美颜模块全部恢复 |
| 开启开关后的聚合任务 dry-run | 每个美颜模块均包含 assembleDebug、testDebugUnitTest、发布任务；退出码 0 |
| Agent 结构与构建规则校验 | 通过，构建规则 0 警告 |

本次只验证停用后的实际构建及开关两态的注册/任务图，未重新运行全量单元测试或真机美颜场景。原迁入测试报告和基线保留其历史含义；已有 build/repo 美颜发布产物不会自动删除。本次未执行远端发布。

恢复命令（PowerShell）：

```powershell
.\gradlew.bat "-Pdevlib.enableBeauty=true" assembleLibraries
```
