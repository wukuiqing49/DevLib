# DevLib

Android/JVM 独立功能库与开发者能力工作台。

App 按功能库、基础组件、工作区三个入口组织，支持素材导入、产物保存与跨工具流转。产品结构与验证边界见 [开发者工作台](doc/developer-workbench.md)。

完整项目介绍、模块列表、构建运行、库接入和验证说明见 [项目 README](doc/README.md)。

Android 17 / API 37 构建基线、Skill 配置及未验证边界见 [Android 17 兼容说明](doc/android17-compatibility.md)。

NAS 与 SAF 存储库：`core_nas`、`core_usb`，包含各自独立的浏览、上传下载及读写验证页面；详见 [接入与测试](doc/nas-usb-migration.md)。

逐库可靠性优化与边界：[优化记录](doc/library-optimization.md)。实际验证见固定测试报告。

统一记录导出：`core_document` 将一份记录生成 PDF、Excel、Word，见 [接入与边界](doc/record-export.md)。
