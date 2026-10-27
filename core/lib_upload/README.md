# lib_upload

FTP/FTPS/HTTP 上传。来源：PressIPTC。类型：Android Library / AAR。

## 接入

```groovy
implementation project(':core:lib_upload')
```

独立发布坐标：`com.wkq.dev:lib-upload:0.1.0-SNAPSHOT`。运行 `:core:lib_upload:publishReleasePublicationToDevLibLocalRepository` 后，在消费工程声明 DevLib 的 `build/repo` 文件仓库；不要同时引入原库的 AAR。

## 宿主责任与边界

宿主配置服务器、凭据、持久化与权限；不把网络重试宣称为字节级续传。

## 验证状态

来源与初始文件摘要见根目录 `docs/import-manifest.json`。迁入后验证统一记录在 `doc/testing/TEST_REPORT.md`；有源码或产物不等于完成真机、跨平台或商用验收。原宿主尚未回接，不宣称迁移闭环完成。

## 去重后的接入边界

SMB、SFTP、WebDAV 只在 `core_nas` 维护，已移除本库的对应客户端、配置类型及 SSHJ/jCIFS 依赖，不保留兼容门面。旧配置读取会明确报不支持并指向 core_nas，原配置与密码不自动删除，也不自动转换协议；消费项目需按 core_nas 的连接配置重新接入，SFTP 需要主机指纹。默认新配置为 FTPS。系统分享统一使用 core_utils.ShareUtil。

## 可靠性与接入边界（2026-10-27）

HTTP 上传关闭自动连接重试和重定向，POST/PUT 不由拦截器重放；仅无请求体的 GET/HEAD/OPTIONS 可有限重试 502/503/504。maxRetry 表示总尝试次数，范围 1..10。服务端可能已提交但客户端未收到响应，宿主须核对结果，不要盲目重新上传。

HTTP 协程取消会取消底层 Call；FTP/FTPS 使用 runInterruptible 并在流读取中检查中断，底层阻塞调用仍受协议超时约束。URI 请求体禁止自动重放并校验声明长度，响应体及时关闭。FTP 临时文件和不确定远端提交由宿主恢复处理；不承诺字节级可靠续传或所有协议原子提交。
