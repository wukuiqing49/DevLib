# lib_transfer

本地/SAF/SMB/WebDAV/SFTP、持久任务与同步。来源：NASFileManager。类型：Android Library / AAR。

## 接入

```groovy
implementation project(':core:lib_transfer')
```

独立发布坐标：`com.wkq.dev:lib-transfer:0.1.0-SNAPSHOT`。运行 `:core:lib_transfer:publishReleasePublicationToDevLibLocalRepository` 后，在消费工程声明 DevLib 的 `build/repo` 文件仓库；不要同时引入原库的 AAR。

## 宿主责任与边界

V2Client 接受 advancedAllowed 回调；通知 Intent 使用宿主 launcher 或 intentFactory，无启动页且未注入时通知不设置点击动作，不影响后台任务。保留 Room schema 和迁移；普通未完成文件重新传输。

## 验证状态

来源与初始文件摘要见根目录 `docs/import-manifest.json`。迁入后验证统一记录在 `doc/testing/TEST_REPORT.md`；有源码或产物不等于完成真机、跨平台或商用验收。原宿主尚未回接，不宣称迁移闭环完成。

更多原始 API 说明见 [README.upstream.md](README.upstream.md)。
