# 库源码目录

[项目说明与全部库接入索引](../README.md#库目录) · [专题文档](../doc/README.md)

| 目录 | 职责 | 模块 |
| --- | --- | --- |
| architecture | 架构底座，8 个模块、5 套体系 | [core_base](architecture/core_base/README.md)、[core_utils](architecture/core_utils/README.md)、[core_network](architecture/core_network/README.md)、[core_router_annotation](architecture/core_router_annotation/README.md)、[core_router_api](architecture/core_router_api/README.md)、[core_router_processor](architecture/core_router_processor/README.md)、[core_router_gradle_plugin](architecture/core_router_gradle_plugin/README.md)、[lib_xpopup](architecture/lib_xpopup/README.md) |
| functional | 独立功能，9 个模块 | [core_archive](functional/core_archive/README.md)、[core_document](functional/core_document/README.md)、[core_image_render](functional/core_image_render/README.md)、[core_qrcode](functional/core_qrcode/README.md)、[core_nas](functional/core_nas/README.md)、[core_usb](functional/core_usb/README.md)、[lib_drop](functional/lib_drop/README.md)、[lib_metadata](functional/lib_metadata/README.md)、[lib_upload](functional/lib_upload/README.md) |
| thirdparty | 第三方平台服务适配 | [core_google](thirdparty/core_google/README.md) |
| internal | 功能库共用的内部契约 | [lib_operations](internal/lib_operations/README.md) |

分类依据是库的职责，而不是来源项目或是否使用第三方依赖。XPopup 属于现有架构底座；NAS 等协议封装仍属于功能库。`core_usb` 提供 Android SAF 存储访问，不是 USB 驱动。

19 个模块的名称、公开包名、发布坐标保持原样；Gradle 逻辑引用仍为 `:core:<module>`，例如 `implementation project(':core:core_document')`。settings.gradle 通过显式 projectDir 映射到分组目录，分类目录本身不是模块。

新增库时同步维护根 settings.gradle、docs/library-catalog.json 的 category / directoryGroup / path、项目档案和演示接入。来源快照与历史测试证据保留当时路径，不作为当前目录入口。新工程生成器的通用模板不受本仓库物理分组影响。

目录迁移保持库实现不变。后续 App 已同步为架构、功能、三方、工作区四入口，详见 [分类与页面对照](../doc/library-organization.md)；下方为目录迁移阶段的验证记录。

## 目录迁移验证（2026-10-30）

- 844 个原 Git 跟踪文件均已迁入且未被忽略规则排除；架构库文件内容保持不变。
- `:app:assembleDebug assembleLibraries testLibraries` 通过，共 827 个构建任务；JUnit 报告合计 333 项，332 通过、1 跳过、0 失败或错误。
- APK 检查确认 19 个模块清单、18 个独立入口及 5 组路由初始化信息一致。
- 目录映射测试 2 项、库目录测试 11 项、生成器测试 8 项、Agent 结构测试 14 项、工作流测试 6 项通过。
- Agent 结构、配置及构建静态检查通过；架构检查保留独立消费示例和 Utils 初始化的两项既有提示。
- 元数据测试文件整文件注释审计发现 8 个未改动方法的既有缺口；本次调整的类和方法已补中文说明，不将整文件审计标为通过。
- 无页面、文案或窗口行为变更，Base/UI/国际化/沉浸式实现沿用现状；未进行设备或视觉验收，未提交、推送或调整 CI。
