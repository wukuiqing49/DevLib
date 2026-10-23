# lib_upload

FTP/FTPS/SFTP/WebDAV/HTTP/SMB 上传。来源：PressIPTC。类型：Android Library / AAR。

## 接入

```groovy
implementation project(':core:lib_upload')
```

独立发布坐标：`com.wkq.dev:lib-upload:0.1.0-SNAPSHOT`。运行 `:core:lib_upload:publishReleasePublicationToDevLibLocalRepository` 后，在消费工程声明 DevLib 的 `build/repo` 文件仓库；不要同时引入原库的 AAR。

## 宿主责任与边界

宿主配置服务器、凭据、持久化与权限；不把网络重试宣称为字节级续传。

## 验证状态

来源与初始文件摘要见根目录 `docs/import-manifest.json`。迁入后验证统一记录在 `doc/testing/TEST_REPORT.md`；有源码或产物不等于完成真机、跨平台或商用验收。原宿主尚未回接，不宣称迁移闭环完成。
