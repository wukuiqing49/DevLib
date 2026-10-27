# lib_metadata

EXIF/IPTC/XMP 读写、容器处理与通用模板模型。来源：PressIPTC。类型：Android Library / AAR。

## 接入

```groovy
implementation project(':core:lib_metadata')
```

独立发布坐标：`com.wkq.dev:lib-metadata:0.1.0-SNAPSHOT`。运行 `:core:lib_metadata:publishReleasePublicationToDevLibLocalRepository` 后，在消费工程声明 DevLib 的 `build/repo` 文件仓库；不要同时引入原库的 AAR。

## 宿主责任与边界

MetadataRecord 为独立 DTO，已移出原照片 DAO/Database/Repository；宿主自行映射业务记录。

## 验证状态

来源与初始文件摘要见根目录 `docs/import-manifest.json`。迁入后验证统一记录在 `doc/testing/TEST_REPORT.md`；有源码或产物不等于完成真机、跨平台或商用验收。原宿主尚未回接，不宣称迁移闭环完成。

## 可靠性与接入边界（2026-10-27）

基础 ExifWriter.writeAllMetadata 在同目录副本完成 EXIF 和可选 XMP 后再替换源文件，避免后半程失败留下只写了一半的元数据。writeBasicMetadata 复用该事务，缺失原始拍摄时间保持未知。相同文件的并发业务操作由宿主串行调度，其他高级写入 API 沿用各自原有契约。

接入分三条路径：基础 EXIF/XMP 读写；高级格式/native 引擎；模板 Room 存储与原生任务保护服务。只有调用相应入口才执行相关路径，但当前 AAR 仍包含这些依赖及 native 资源，并未实现按需下载或物理拆包。不要把依赖本库描述成完全不含数据库、服务或 native。

原图完整性保护沿用各格式校验；native ABI/16KB、HEIF、后台限制和复杂格式仍需专项验收。迁入与 JVM 测试不等于原图所有格式均可无损写入。
