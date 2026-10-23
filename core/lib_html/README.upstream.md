# lib_html

离线浏览器接收页：HTML/CSS/JS、中英文资源全部打包在 `src/main/assets/droplan_web`，无 CDN、在线认证、外部脚本或字体。

`WebAssetCatalog` 只接收已解析的 URL path，采用精确白名单；未知路径返回 null。HTTP 层负责认证、状态码和安全响应头，`OfflineWebAssets` 仅打开资源流，响应发送方负责关闭。

已实现 fragment 配对入口及清除、审批等待、授权恢复、分页清单、文件能力说明、排队下载、许可领取、取消/重试、服务端发送状态、流式有界测速与网络错误指引，支持暗色及窄屏。

文件名用 textContent 渲染；下载使用浏览器同源原生导航与 Cookie，不 fetch 整文件或构造 Blob。自动启动受阻时提供“开始下载”按钮，SENT 不冒称接收落盘。

HTTPS 入口所有 API 与原生下载保持同源。安全上下文且支持 `showSaveFilePicker` 时，额外显示 WebSocket 流式保存；使用 `droplan.v1`、Cookie 和既有任务，每写完最多 64KiB 才请求下一块。HTTPS 自动使用 WSS，否则 WS。不支持流式文件保存时只保留原生下载，不使用全文件 Blob 兜底。该路径暂不提供断点恢复；失败后可重新排队原生下载。浏览器能力不代表无线速度更快。

不依赖 lib_drop，不处理端口或文件授权；由 `feature_app/ShareService.kt` 适配资源与协议。API 约定见 `core/lib_drop/README.md`。

优先同源 EventSource SSE：完整会话任务快照与心跳；连接失败/超限/浏览器不支持时降级前台 2 秒、后台 5 秒轮询，错误后十五秒尝试恢复 SSE。关闭页面的状态连接不取消原生下载，页面任务列表不持久化。发送端预生成完成的 ZIP 和普通文件一样显示在清单，通过相同队列原生下载，不在网页打包或组装 Blob。自动续传、后台保存与大文件限制需要各浏览器单独验收，桌面 Chromium 测试不能替代 iOS/macOS/Linux 验收。
