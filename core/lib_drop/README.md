# lib_drop

LAN 分享、配对、HTTP/HTTPS/WebSocket 下载。来源：DropLAN。类型：Android Library / AAR。

## 接入

```groovy
implementation project(':core:lib_drop')
```

独立发布坐标：`com.wkq.dev:lib-drop:0.1.0-SNAPSHOT`。运行 `:core:lib_drop:publishReleasePublicationToDevLibLocalRepository` 后，在消费工程声明 DevLib 的 `build/repo` 文件仓库；不要同时引入原库的 AAR。

## 宿主责任与边界

只读分享，不是 NAS 文件管理；宿主负责权限、服务生命周期、证书与用户确认。

## 验证状态

来源与初始文件摘要见根目录 `docs/import-manifest.json`。迁入后验证统一记录在 `doc/testing/TEST_REPORT.md`；有源码或产物不等于完成真机、跨平台或商用验收。原宿主尚未回接，不宣称迁移闭环完成。

更多原始 API 说明见 [README.upstream.md](README.upstream.md)。

## 可靠性与接入边界（2026-10-27）

结束分享时，即使单个流关闭回调失败，也会继续撤销其余传输、停止监听及清理线程池，最后报告聚合错误。关闭操作可重复调用。

浏览器测试夹具加载 feature_demo_files 中现有 demo_receiver，不依赖已移出的 lib_html。宿主仍需在网络切换、页面/服务退出时结束对应分享，并管理证书、用户审批和权限；Range 重试不等于持久后台传输。
