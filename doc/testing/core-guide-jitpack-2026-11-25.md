# core_guide JitPack 发布记录

日期：2026-11-25。目标：将已完成的通用引导库作为可远程引用的 AAR 发布，而不是添加网络请求能力。

版本：模块 `0.1.0`，Git 标签 `guide-v0.1.0`，JitPack 坐标 `com.github.wukuiqing49.DevLib:core-guide:guide-v0.1.0`。

本轮将 core_guide 加入既有 jitpack.yml 的明确发布列表及通用 JitPack 坐标映射，不新增 CI，不修改其他库的普通 Maven 版本。包含 release AAR、sources JAR、POM 和 module metadata，运行依赖限于 AndroidX。

独立消费示例 `samples/guide-consumer` 提供 `-PguideRemote`：只从 JitPack 解析该坐标，不注册本地 Maven，编译公开 API，并校验坐标及意外宿主依赖。设备验证沿用 [V3 记录](core-guide-v3-2026-11-25.md)，不把发布构建当作新增设备验收。

发布进行中；最终远程构建、下载及消费结果将在操作完成后补充。此处坐标在远程成功前属于待验证发布目标。

依据：[JitPack 多模块与构建配置](https://docs.jitpack.io/building/)。
