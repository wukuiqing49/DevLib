# lib_document

轻量 DOCX/XLSX OpenXML 写入。来源：RoofQuote。类型：Kotlin/JVM JAR。

## 接入

```groovy
implementation project(':core:lib_document')
```

独立发布坐标：`com.wkq.dev:lib-document:0.1.0-SNAPSHOT`。运行 `:core:lib_document:publishReleasePublicationToDevLibLocalRepository` 后，在消费工程声明 DevLib 的 `build/repo` 文件仓库；不要同时引入原库的 AAR。

## 宿主责任与边界

纯 JVM；只覆盖已有写入子集，不提供通用 Office 编辑器。

## 验证状态

来源与初始文件摘要见根目录 `docs/import-manifest.json`。迁入后验证统一记录在 `doc/testing/TEST_REPORT.md`；有源码或产物不等于完成真机、跨平台或商用验收。原宿主尚未回接，不宣称迁移闭环完成。

## 可靠性与接入边界（2026-10-27）

DOCX/XLSX 在同目录临时文件完成 ZIP 写入和同步后使用原子替换，支持覆盖已有导出；文件系统不支持原子移动时明确失败并保留旧文件，不先删除正式文件。每个条目和媒体复制块检查线程中断，失败清理临时输出。

XLSX 拒绝超出 1048576 行、16384 列、单元格 32767 UTF-16 单元以及包含非法 XML 字符的工作表名，不静默截断。当前是轻量写入子集，没有完整 Office 排版、公式引擎或编辑能力；实际阅读器视觉结果仍需验收。
