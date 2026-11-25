# core_guide JitPack 发布记录

日期：2026-11-25。目标：将已完成的通用引导库作为可远程引用的 AAR 发布，而不是添加网络请求能力。

版本：模块 `0.1.0`，Git 标签 `guide-v0.1.0`，JitPack 坐标 `com.github.wukuiqing49.DevLib:core-guide:guide-v0.1.0`。

本轮将 core_guide 加入既有 jitpack.yml 的明确发布列表及通用 JitPack 坐标映射，不新增 CI，不修改其他库的普通 Maven 版本。包含 release AAR、sources JAR、POM 和 module metadata，运行依赖限于 AndroidX。

独立消费示例 `samples/guide-consumer` 提供 `-PguideRemote`：只从 JitPack 解析该坐标，不注册本地 Maven，编译公开 API，并校验坐标及意外宿主依赖。设备验证沿用 [V3 记录](core-guide-v3-2026-11-25.md)，不把发布构建当作新增设备验收。

## 已验证结果

- 已推送提交 `00e66abb4665be232c232a0874073b9c77e5a959` 和不可变标签 `guide-v0.1.0`。未包含工作区的无关推广 Skill 和网络库文档变更。
- [JitPack 构建日志](https://jitpack.io/com/github/wukuiqing49/DevLib/guide-v0.1.0/build.log) 对应该提交，状态 `ok`，JDK 17，模块包含 core-guide。完整既有发布链本地执行也成功。
- 公开 HTTPS 地址实际下载 POM、AAR、sources JAR 成功。AAR 为 65,331 字节，包含 classes.jar，源码包包含 13 个条目。
- AAR SHA-256：`536d6e573793e7debef40118535d4e4bfc08fd9d99a415a7d054fcca573ca060`。
- 独立消费者使用 `-PguideRemote --refresh-dependencies` 成功，解析实际远程坐标及相同 SHA-256，没有本地 Maven 或宿主库依赖。再次清理输出后编译，避免只以已有编译缓存作为证明。
- 库本轮单测任务复用前轮 42 项通过结果（UP-TO-DATE）；布局脚本 3 项、目录工具 11 项通过。目录工具首次用包式入口因 Python 导入路径失败，改用该脚本实际入口后通过。

构建第一次请求 POM 等待期间超时，随后 JitPack 构建完成，重新下载成功；没有降低 TLS 或用本地产物冒充远端。此处验证编译与下载，不额外宣称 API 35/37 真机适配。

可复核日志位于 `evidence/2026-11-25-core-guide-jitpack/`。0.1.0 是初始库版本；OPPO API 30 已测场景和未完成矩阵仍以 V3 记录为准。

依据：[JitPack 多模块与构建配置](https://docs.jitpack.io/building/)。
