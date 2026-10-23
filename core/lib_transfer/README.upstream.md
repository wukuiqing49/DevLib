# core_transfer

一个可复用的 Android Library，包名 `com.wkq.nas.transfer`。依赖基础 Android/AndroidX、jcifs-ng、sshj 和 OkHttp，不依赖 app、媒体、报告、计费或 UI 模块。来源审计见根目录 `CURRENT_STATE.md`。

## 接入

Gradle：`implementation project(':core:core_transfer')`，将版本目录中的该模块依赖和 KSP 配置一起迁移。库 Manifest 合并网络权限、通知权限和 WorkManager dataSync 前台服务类型。宿主使用默认 WorkManager 初始化；若宿主自定义 WorkerFactory，必须允许创建本模块 Worker。

应用启动时在应用级 IO 协程调用 `TransferClient.get(context).recoverPendingTransfers()`。当前 NASFileManager 已接入。宿主负责提供本地根目录、请求 SAF 树授权并保存持久 URI 权限，以及在需要时请求 Android 13+ 通知权限；模块不会自行弹出权限 UI。

以下操作均从协程调用：

```kotlin
val transfers = TransferClient.get(context)
transfers.saveConnection("phone", ConnectionConfig(StorageProtocol.LOCAL, context.filesDir.absolutePath))
transfers.saveConnection("nas", ConnectionConfig(StorageProtocol.SMB, "smb://192.168.1.10/share/", username = username), password)
val id = transfers.enqueue(TransferRequest(
    sourceId = "phone", sourcePath = "report.pdf",
    targetId = "nas", targetPath = "reports/report.pdf",
    kind = TransferKind.UPLOAD
))
transfers.observeTasks().collect { tasks -> /* 交给宿主 ViewModel */ }
```

上传、下载和跨协议复制使用同一请求契约，区别为源目标连接与 kind。所有任务路径为相对连接 root 的路径，不能传 file URI、SAF Document URI、绝对路径或 `..`。WebDAV root 必须为 HTTPS；SFTP root 为 `sftp://host:22/path/`，需提供与服务器核验过的 OpenSSH `SHA256:` 公钥指纹。密码只保存在 Android Keystore AES-GCM 密文连接表；连接 ID 的协议与 root 不可改动，凭据可更新，改变根目录需新 ID。

单向同步：

```kotlin
val preview = transfers.previewSync("phone", "photos", "nas", "backup/photos")
// 宿主展示 ADD/SKIP/CONFLICT 并获得用户确认。
val ids = transfers.enqueueSync(preview, conflicts = ConflictPolicy.KEEP_BOTH)
```

提交时重新扫描；预览已变化会拒绝提交，需刷新预览。SKIP 为大小和秒级 mtime 快速比较，不是内容哈希一致性证明。同步包括空目录，保留目标额外文件，不做镜像或删除。某些 Provider 无法保存原 mtime，再次同步可能显示冲突，需选择保留两份或跳过。

## 队列语义

- WorkManager 分本地/网络唯一队列，网络队列要求 CONNECTED，本地队列没有网络约束；默认模块内部互斥执行；parallelQueues 开启时本地与网络各执行一个文件，总计最多两个。每条队列始终单独互斥，避免切换策略造成重复认领。
- Room 持久化批次、逐文件状态、字节进度、错误码与重试时间。网络/可重试 IO 错误最多自动尝试 5 次；等待重试的项不会阻塞本轮其他可执行项。
- `pause(id)`、`cancel(id)`、`resume(id)` 返回是否成功改变状态；COMMITTING 不允许暂停/取消。暂停和取消在读写检查点生效，受当前阻塞 IO 超时影响，不承诺即时停止。
- 取消不删除已有目标。暂停保留临时文件，恢复会重新传输；取消或最终失败可能保留任务专属 `.nas-<id>.part`，宿主可提示用户清理，不能把临时文件当完成文件。
- 未完成文件从头重传，已完成项不重传；不宣称字节级断点续传。传输写临时文件，大小校验后提交。冲突策略包含 FAIL、SKIP、KEEP_BOTH 和显式确认的 REPLACE；REPLACE 只替换普通文件，先备份旧目标，再提交并强制哈希校验新内容。校验失败保留旧版本；存在并发目标时不自动覆盖恢复。
- 提交前持久化源内容 SHA-256；发生提交结果不确定时重新读取目标哈希确认，避免进程退出造成重复完成或损坏误报。正常传输默认验证大小，可通过 TransferPreferences.verifyContent 或 TransferRequest.verifyContent 启用目标 SHA-256 校验；选择在任务入队时持久化。启用内容校验时，同步对快速比较相同的文件补充哈希比较。
- 本地/SAF 提交采用排他创建加复制，不保证原子可见；源文件始终保留。进程在提交复制期间退出可能留下不完整最终文件，恢复检测到哈希不符会报告冲突，不能宣称成功或自动删除外部文件。
- Android Keystore 密钥不可跨设备迁移。不要把数据库恢复到另一设备后假定凭据仍可解密。

Android 16 长时间 WorkManager 前台任务仍受系统 JobScheduler 配额限制，恢复机制不代表无限后台执行：[官方说明](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)。本轮实现不新增定时同步或绕过系统调度的服务。

## 验证

```powershell
.\gradlew.bat :core:core_transfer:testDebugUnitTest :app:assembleDebug
```

SMB 相对路径不支持 `?` 和 `#`，避免被 jcifs 的 URL 解析改变实际目标；连接根目录可以使用 URI 编码的空格。

JVM/Robolectric 检查差异规划、路径、流式传输、冲突、恢复哈希、Room 状态竞争、Worker 队列和 MockWebServer WebDAV。真实 SMB/SFTP、USB/SAF、后台配额与设备进程恢复以 `doc/testing/TEST_REPORT.md` 的实际证据为准。

## 新增契约与恢复

- Provider 实例报告 capabilities；FileClient.capabilities(connectionId) 查询实例能力，旧协议枚举方法只保留兼容。未支持的原子提交、随机写、服务端复制及协议分页不会虚报支持。
- FileClient.listPage 对过滤后的结果使用有界 Top-N；LOCAL API 26+ 与 SAF 使用流式枚举。SMB/WebDAV/SFTP 的底层列举仍可能返回完整目录，不宣称服务端分页。跨目录同步扫描仍有 100000 项上限。
- 批量传输入队复用源连接会话。任务页面按筛选分批读取，默认 200 项，完整统计由数据库聚合；taskPage / operationPage / operationJobPage 分批导出历史。清理不删除文件，保留失败传输和可能带备份的取消记录。
- 复制使用 previewTransfers 获取目标快照，宿主展示并确认后 enqueueBatch。REPLACE 未提供目标预览会拒绝；同步确认会重新比较源和目标快照。
- 替换备份名为 .nas-<task UUID>.backup，禁止作为普通文件操作入口。恢复受阻时可在失败任务菜单恢复旧版本；已有目标时使用新名称，保留双方内容。目录与文件类型冲突不会自动递归替换。
- FileClient.enqueueOperation 将已确认的变更预览保存到 Room，再调度 MutationWorker；进程中断的运行项标失败，不能自动重放。宿主启动补调度 MutationWorker.schedule(context)，页面可观察 observeOperationJobs。
- SavedSyncProfile 保存手动同步配置；运行时重新扫描并确认，没有定时执行。
- 远端身份统一默认端口，SMB 路径按大小写不敏感保护；外部存储 SAF 使用树 Document ID 识别重叠根。不透明且不同的同 authority SAF 树保守拒绝组合，不能假定它们互不重叠。DNS 别名、跨协议同一存储和服务端挂载别名无法仅凭 URL 完全判定。
- Room v3 显式支持 v1→v2→v3，保留凭据和传输检查点，不使用破坏性迁移。

## V2 扩展

`V2Client` 提供收藏、连接交换、内容查重、规则归档、高级同步与回收；`AdvancedSyncPlanner` 为纯 JVM 的双端基线/墓碑决策；`V2Worker` 持久串行执行，周期发现高风险同步差异时等待手动确认。Room v4 新增通用版本化记录表，3→4 迁移保留全部既有表。

`NasDiscovery` 查询局域网 DNS-SD 广播；`ConnectionExchange` 严格限制公开字段、1 MB 输入及 100 个配置，不包含口令或 SAF 授权。`previewContent` 有界读取，页面只支持 UTF-8 文本与缩放图片。

回收先复制、校验内容与源快照，再删除原位置；恢复不覆盖已有目标。复合任务中断后标失败，重新预览，不重放破坏性操作。V2 同时只接受一个活动复合任务，已结束计划移除大体积预览负载，保留任务摘要、逐文件复制记录和回收记录。协议能力、规模及外部验收限制详见根目录 V2_IMPLEMENTATION_PLAN.md。
