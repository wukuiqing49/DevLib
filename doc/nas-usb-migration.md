# NAS / USB 独立库接入

日期：2026-10-27。`lib_nas` → `core/core_nas`，`lib_usb` → `core/core_usb`。二者为功能库，不属于基础体系；Base、Utils、Network、Routers 和 XPopup 未改动。

| 模块 | 公开包名 | Maven 坐标 |
| --- | --- | --- |
| core_nas | com.wkq.naslib | com.wkq.dev:core-nas:0.1.0-SNAPSHOT |
| core_usb | com.wkq.usb | com.wkq.dev:core-usb:0.1.0-SNAPSHOT |

两个模块独立声明依赖和 consumer rules，compileSdk 37、minSdk 26、JVM 17。源码来源及原始 SHA-256 见 docs/import-manifest.json；README.upstream.md 保留上游说明，接入时使用各库当前 README。

## 工作台业务流程

NAS：工具 → 文件与传输 → NAS 文件管理。选择 SMB / WebDAV / SFTP，输入根地址及会话凭据，连接并浏览目录；点击目录下钻、点击文件下载到工作区，可返回上级。局域网发现只列出广播服务，不自动连接或信任主机。SFTP 必须填写已确认的 SHA256 主机指纹；HTTPS 使用系统信任，不自动信任自签名证书。地址不能嵌入账号密码。表单不保存凭据，也不把连接参数记录进操作历史。

USB：工具 → 文件与传输 → USB / SAF 文件管理。通过系统选择器授予目录权限，按系统实际返回 flags 尝试持久授权；临时授权与持久授权分别提示。浏览时检查真实权限，目录 URI 可随状态恢复。系统没有提供的盘不会凭空显示；不是 USB 驱动，不提供文件系统格式化。

两个页面都继承公共 DemoActivity / BaseVMTitleActivity 和 DemoViewModel / BaseViewModel，列表使用 BaseRecyclerViewAdapter，写入确认使用公共弹窗，产物进入工作区保存/分享。XML + ViewBinding，中文与英文资源完整；Insets 由 Base 接管，不重复叠加。

## 测试功能

1. 先连接或授权，再浏览目标目录。页面最多展示 200 条，并显示实际枚举数量；这不是服务端分页。
2. 下载：点击文件，读取到独立工作区输出，限制 8 MiB。失败或取消清理本次本地产物。
3. 上传：选择一个且仅一个本地文件，填写新目标名，确认后独占新建；不覆盖已有目标。刷新可查看结果。
4. 读写验证：确认后创建 UUID 命名的 4 KiB 文件，写入、读回逐字节校验并删除；校验或取消时清理已成功创建的文件。写入失败不擅自删除未知归属目标，异常提示检查残留路径。
5. 存储端失败、断网、拔盘或取消可能留下部分远端文件。确认文案明确此限制，不宣称任意提供方支持事务或原子移动。

JVM 测试包含 NAS 8 项、USB 10 项及宿主隔离读写 7 项；另回归工作区和上传协议提示。协议测试使用本地夹具，不替代真实 NAS / USB 测试。

## 替换边界

`lib_transfer` 及旧页面已退出当前模块和发布清单。没有迁入 Room、WorkManager、后台同步、权益门禁或原产品队列；旧任务数据库和凭据不做自动迁移。应用工作区既有文件保留，旧路由不会继续执行退役任务。`lib_upload` 继续负责 FTP / FTPS / HTTP，SMB / SFTP / WebDAV 提示统一指向 core_nas。

## 验证边界

本轮不执行 adb、真机或模拟器。Android 17 权限运行、真实协议固件、USB 拔盘、提供方授权恢复、日夜主题与大字体视觉验收仍待实测。构建成功与静态检查不能替代这些验收。最新结果见 testing/TEST_REPORT.md。
