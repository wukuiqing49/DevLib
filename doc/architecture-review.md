# DevLib 架构与范围

当前保留 19 个模块：8 个基础模块、10 个独立功能库、1 个功能内部支撑库。共 15 个 Android AAR 和 4 个 JVM JAR，无模型 Asset Pack。

基础体系为 Base、Utils、Network、Routers 四构件、XPopup；保持原有实现，不因功能筛选重写。上一轮误改的 ShareUtil 已按补丁撤回。

功能库保留压缩、轻量图片、二维码、元数据、图片 PDF、Office 写入、局域网分享、多协议上传和多存储传输，以及用户确认新增的统一 Google SDK 适配 core_google。lib_operations 为这些能力的预算/取消/进度与输出支撑，不独立制造业务入口。

已移出分散登录、支付权益库、拍照定位、主动崩溃、原产品网页、报告模板、Nearby、媒体产物管理、证件照、Native 图片引擎、RAW 与人像模型。来源原仓库保持不动；本仓库实际模块及来源见 docs/library-catalog.json 与 docs/import-manifest.json。

core 不依赖演示层。Demo 页分别放 foundation/files/image/document/google，使用公共 DemoActivity 和 DemoViewModel。局域网页面仅在演示 feature 中维护，不再作为功能库发布。

App 已取消人像 Asset Pack、定位和 Nearby 等配套声明。最终 Manifest 仍可能包含保留二维码库声明的 CAMERA；不能将移出 lib_camera_capture 描述为所有相机能力都已删除。

独立消费工程同步移除被排除的坐标和公开类型。当前库保留迁入 API 包名，避免无意义改名。真实服务端、设备、发布 Native 与 ABI 验收不由构建成功替代。

2026-10-26 已按实际 Gradle 插件纠正产物类型：lib_operations 是 Android AAR，合计 15 AAR / 4 JVM。目录脚本在构建前校验插件与 artifactType 的一致性，防止文档再次把偏通用 Kotlin 源码误当 JVM 库。
