# DropLAN 现状审计与 P0 设计

> 历史 DropLAN 专项参考，仅明确指定该产品时适用；不代表 DevLib 当前能力或工程事实。
## 加载

- `AGENTS.md`
- `Android_Offline_File_Distribution_Development_Plan.md`
- `.agents/config/project-profile.yml`
- `.agents/rules/execution.md`
- `.agents/rules/droplan.md`
- `$android-project-architecture-workflow`
- 涉及构建与协议依赖时加载 `$android-build-workflow`。

## 流程

1. 核对实际目录、构建文件、阶段状态和已有代码；未找到工程时明确未初始化，不把脚手架参数、规格或模板当实现证据。
2. 按五项核心目标检查覆盖：多端、离线、大文件、免安装、最快可用；记录实际路径、已验证结果、缺失和假设。
3. 规划 P0：工程基线、权限/版本、热点与地址发现、手动热点/LAN 回退、最小鉴权、SAF 流式下载、前台服务、有界测速。
4. 分离连接模式、服务监听/发布地址、文件源、接收授权、任务调度、能力识别和测速推荐职责；不要默认 Room、WorkManager 或客户端网络库能替代 HTTP 服务端。
5. 给出 Windows/Android/iPhone 或 iPad 的逐项测试步骤与哈希检查，按规格分别验证超过 4GiB 文件、两台并发及两种连接路径比较。
6. 若任务仅分析则交付现状与计划；若用户已要求实现当前阶段，完成必要计划后继续执行，不重复索要已给出的授权。可复用报告按任务需要维护在 `doc/`，不为简单配置修改生成无关报告。
7. P0 未实测通过不进入 P1A/P1B/P2。缺少硬件、失败或受阻如实记录；初始化和编译成功不代表设备功能验收。
