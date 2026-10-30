# lib_drop

DropLAN 协议与传输库，不依赖网页库或业务 UI。通过 `StaticAssetProvider` 注入静态资源，组合示例见 `feature/feature_app/ShareService.kt`。

## 接入

```kotlin
val engine = ShareEngine(sources) // 默认等待本机审批。
val server = DropServer(chosenIpv4, 0, engine, staticAssets)
server.start()
val receiverUrl = server.entryUrl // 含入口凭据，只向可信接收者展示，不写日志。
// 本机审批：engine.pendingPairings()、approve(id)、reject(id)
// 撤销：engine.receivers()、revoke(id)；暂停新任务：pauseNewDownloads(true)
// 结束及失败路径：server.close()
```

SAF 源创建、服务启动与清理应在工作线程调用。显式选择 IPv4，不默认监听所有接口；`LocalAddressDiscovery` 仅提供候选，必须由接收端验证可达。

## 协议与限额

HTTP 与 HTTPS 共用同一 API/调度。`DropServer(..., tls = TlsIdentity.load(pkcs12Stream, password, chosenIpv4))` 启用 HTTPS，仅 TLS 1.2/1.3，Cookie 增加 Secure，Origin 必须匹配协议。证书最多 1MiB、仅一个私钥身份、必须有效且 IP SAN 匹配监听地址；接收端须信任签发机构。不会联网签发、自动安装 CA、关闭校验或静默降级 HTTP。Android 页面可通过 SAF 选择本地 PKCS12 并输入仅本次使用的密码，结束时关闭服务。

WebSocket：`GET /api/v1/ws/files/{fileId}?task={taskId}`，子协议 `droplan.v1`，同源 Cookie、严格 Origin 与原任务归属/并发限制。HTTPS 使用 WSS。握手后客户端发送掩码文本 `next`，服务端返回一个最多 64KiB 的 binary 帧；客户端写完才请求下一块。EOF 返回 `{"done":true,"bytes":"十进制长度"}`；客户端关闭写句柄后回复 `saved`，服务端发送 1000 close。ping/pong/close 受限支持；客户端消息至多 125 字节，拒绝分片、扩展及非预期数据，不接受上传。断流/源短读/超时标失败，取消/撤销关闭流和 Socket。该路径当前为顺序传输，恢复用新的任务或原生 HTTP Range，不宣称 WS 自动续传。

网页仅在安全上下文且支持 `showSaveFilePicker` 时提供 WebSocket 流式保存，逐块等待磁盘写入，不构造文件 Blob。不支持时使用原生 HTTP/HTTPS 下载；普通局域网 HTTP 通常不具备这个安全上下文。跨端支持与速度必须实测，不预设 WebSocket 比 HTTP 快。

基础路径 `/api/v1`：配对申请/查询 `/pairings`、当前会话 `/session`、分页清单 `/manifest`、创建/查询/取消 `/downloads`、`GET/HEAD /files/{id}`、有界测速 `/probe`。无上传、删除文件、任意目录或管理端点。

- 高熵入口凭据、两分钟配对票据、发送端审批、独立一小时 Cookie（HttpOnly、SameSite=Strict），内存保存，可撤销。HTTP 无加密。
- 幂等任务、30 秒领取许可、按会话轮转；默认全局两路/每会话一路，重复 GET/Range 同样受限，HEAD 不占名额。
- Long 长度/偏移、64KiB 源缓冲、HTTP 固定小缓冲、背压；取消/撤销关闭流与 Socket，30 秒无输出进展断开连接。
- HEAD、200/206/416、开放/后缀区间、多段忽略；未知长度或不可 seek 降级为顺序下载。
- 仅可信 SHA-256 内容版本生成强 ETag；If-Range 不匹配完整响应。SAF 不宣称稳定版本，源文件分享期间不得修改。
- 16 个连接、16 个接收会话、32 个短期配对、128 条任务记录、每会话 16 个待处理任务、4096 字节 JSON 体、每页 50 项、每会话每秒 60 个请求；超限返回 429/Retry-After。
- 测速最多 3 秒或 32MiB，每次一条；禁缓存/压缩，有下载时拒绝测速，新下载取消已有测速。
- 严格 Host/Origin 校验，状态变更要求 `X-DropLAN: 1`，不开放通配 CORS，错误不暴露源 URI/堆栈。

JSON 的 `size`、`bytesSent` 使用十进制字符串，未知大小为 null，避免浏览器整数精度损失。SENT 只表示本次响应发送完成，不表示接收落盘或哈希校验成功。

`UriShareSource.create` 仅接受 SAF 授权 content URI，默认允许已知系统本地提供者；通过实际描述符验证 seek，不将 URI 转为路径。其他提供者需先准备本地文件。

`ConnectionAdvisor` 已通过鉴权 `POST /api/v1/advice` 接入接收网页。请求最多两条 HTTP/WebSocket 有限正数速率样本，可指定已授权清单中的 fileId 估算时间；空样本/单样本不宣称完成比较。网页仅复用本页真实任务或 HTTP 测速结果，样本两分钟失效、网络变化清除，不持久化；历史恢复的任务不作为新观测。推荐仅比较当前连接中已观测的传输方式，不代表其他网络或磁盘落盘性能。选择推荐方式只影响后续新任务，有未结束任务时禁用切换；不自动切换网络或重新发起下载。实际跨网络测量与历史持久化仍未接入。

## 当前边界

`GET /api/v1/events` 已实现 SSE，`/session.events=true`。与文件相同的 Cookie/Host/Origin 校验；全局最多四路、每会话一路，不占下载许可。连接立即发送 `snapshot`（仅本会话的任务），状态变化最多每秒推送一次，空闲每五秒发送 `heartbeat`。重连发送完整快照而非伪造事件历史重放。断开只释放状态连接，不取消文件；撤销/过期关闭状态流。网页错误时退回轮询，十五秒后尝试恢复 SSE。

`SnapshotZipArchive.prepare` 在应用控制的独立目录预生成静态 ZIP，最多 999 个原文件；提供 STORE（CRC/长度预扫描，两次读源）和 DEFLATE（level 1）。流式 64KiB 缓冲，输入/输出/验证均受 quota 限制，生成后完整读回核对 CRC/长度并计算归档 SHA-256，完成才发布到同一授权文件清单。归档支持 Long Range 和可信强 ETag，结束分享清理缓存。文件名去路径并加入唯一序号，不接受远程归档/文件修改 API。ZIP64 由 Android/JDK ZIP 实现自动处理；JVM 已验证实际超过 4GiB 的条目，Android 原生 ZIP64 与各平台解压仍须设备验证。STORE 两次读取期间源改变会拒绝发布，不保证被修改中的原文件快照。

`BrowserNetworkController` 使用公开 API 创建 LocalOnlyHotspot 或 Wi-Fi Direct Group Owner；接收端在系统 Wi-Fi 加入，再使用浏览器。读取系统实际 SSID/密码，P2P 地址来自连接信息，不硬编码网关。权限/旧系统定位服务检查、超时、迟到回调释放、既有 P2P 组不接管、只释放本应用资源。API33+ 请求 NEARBY_WIFI_DEVICES，旧系统请求 COARSE+FINE；网络由 feature_app 前台服务持有。已启动分享时拒绝建网/切换；网络丢失关闭文件服务。P2P 普通 Wi-Fi 接入取决于接收设备，不能声称跨端全部可用。

网页链接二维码由 feature_app/core_utils 实现；尚无 Wi-Fi 加网二维码、持久化历史或跨网络实际横向测速；未加入需要专用接收端的 Nearby/自定义 Socket，也不宣称 AirDrop/Quick Share 互通。热点/P2P 真机、SAF、后台和跨端大文件兼容性未验收，见仓库 `doc/testing/`。

`LocalNetworkMonitor` 监听非默认网络及无互联网 Wi-Fi 的地址变化，防抖300ms；页面可见时每五秒兜底检查系统未暴露的热点接口。LiveData 无活动观察者时注销回调并停止检查，不操作或切换系统网络。
