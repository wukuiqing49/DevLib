# DevLib 最新增量验证报告

日期：2026-10-23。范围：公开依赖、定位 debug 适配及测试中心。HEAD `76725ecb17891007db8c57815ae39b0973cc5b2d`，包含 staged/unstaged/untracked 未提交修改。

源码/构建输入 SHA-256：`813e931e58bcd0ee142fd202e58353172ad80c9696010460b31644f45f15fdb9`，2365 个 Git 可见输入文件（排除文档、Agent 规则、忽略的缓存和本机私有配置）。产物 APK 摘要见 [证据](evidence/2026-10-23-test-lab/summary.json)。

## 结果

| 用例 | 状态 | 结果 |
| --- | --- | --- |
| LAB-BUILD | 本次通过 | App debug、feature 测试 APK、camera_capture debug/release 构建通过；最终构建 13 秒。 |
| API-UNIT | 本次通过 | core_pdf 5、core_qrcode 13、core_qr_transfer 10、lib_location 7，共 35 项单元测试，0 失败/错误/跳过；初轮构建测试 1 分 29 秒。 |
| API-PUBLISH | 本次通过 | 五个受影响库 release 本地 Maven 发布通过；测试 APK 与发布聚合耗时 25 秒。 |
| API-CONSUMER | 本次通过 | 仅发布坐标的独立宿主可编译 PDF ImageSource、CameraX/Lifecycle、协程状态及扫描 Activity；19 秒。 |
| AMAP-GUARD | 本次通过 | 默认无 Key 可构建拍摄库 debug；显式开启高德但缺少 Key 时按预期给出配置错误。 |
| STATIC | 本次通过 | Agent、构建、UI、国际化和架构检查通过；架构存在 2 条既有启发式警告：独立消费工程未纳入根 settings，以及 CoreUtils 名称扫描多处命中。 |
| LAB-DEVICE | 未执行 | 新增 7 项设备端功能用例，仅编译。用户要求不用真机测试，未安装测试 APK、未运行 instrumentation。 |
| LAB-UI / LAN-PEER | 未执行 | 图片/文件打开、分享、二维码视觉效果、局域网接收端审批下载、小屏/旋转/主题/字体矩阵均未做设备验收。 |

## 已实现

- 测试首页及图片/二维码、文档导出、压缩/局域网三个页面，真实调用迁入库。
- 示例图片、原样导入、元数据检查、水印、二维码回读、PDF 首页预览、DOCX/XLSX 结构检查、ZIP 内容校验、产物打开/分享。
- LAN 使用选定 Wi-Fi/以太网地址、手动审批；离开前台关闭服务；IO 工作与 ViewModel 状态分离。
- 公开依赖 api 边界修复；高德 debug 可选；美颜继续停用、DevHub 排除。

## 设备操作边界

用户补充“不用真机测试”之前已执行一次 App APK 覆盖安装及启动，安装 Success、启动 Status ok。之后未继续真机操作；这一记录只证明当时 APK 能启动，不能代替最终源码版本的页面或功能验收。已有设备上数据未清除。

本轮没有实际登录、购买、外发文件或触发崩溃。服务器上传、NAS/Nearby、Native 模型及 Play 配送均未执行；Billing → Entitlement 仍需宿主策略适配，未在本轮加入新的授权后端。

## 证据与后续

- [逐模块摘要](evidence/2026-10-23-test-lab/summary.json)、[执行命令](evidence/2026-10-23-test-lab/commands.txt)。
- 本机原始日志：`.ai-work/migration/test-lab-*.log`；不提交缓存与大日志。
- [首次迁入历史报告](IMPORT_REPORT.md)、[移除 DevHub 的范围验证](DEVHUB_REMOVAL.md)保留历史，785/617 数字不作为本轮复测总数。
- [页面说明](../test-lab.md)。本轮不提交或推送。
