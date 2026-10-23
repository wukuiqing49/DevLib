> 历史分析：下文为收紧来源前的 44 模块规划。当前已移出 Starxen、BeautySDK，实际 34 模块及实现见 [Demo 说明](test-lab.md) 和 [项目 README](README.md)。

# Demo 逐库业务页面重构分析

日期：2026-10-23。基于当前源码复核。本轮只形成方案，不修改页面，不运行真机测试。

## 结论与现状差距

Demo 应成为每个库的独立接入范例：拥有自己的入口、输入、配置、执行过程和结果，可以沿着一条业务流程了解该库的作用、限制和失败处理。

当前三类测试面板可以保留为内部诊断参考，但不适合作为正式 Demo 的首页和页面模型。

| 当前实现 | 实际问题 | 重构方向 |
| --- | --- | --- |
| LabHomeFragment 只有图片、文档、传输三个入口 | 用户无法从库名找到对应示例，绝大多数库没有入口 | 首页为逐库目录，分类与搜索只负责找到库；每个启用模块有自己的入口和接入状态 |
| LabPageFragment 通过 page 字符串切换按钮 | 元数据、水印、二维码等不同流程共用同一布局，缺少参数和业务层次 | 每库独立 Activity/Fragment、XML 和 ViewModel；库内多个能力使用子流程 |
| LabViewModel 使用 activityViewModels | 三页共享 source/output/status，文档结果可能成为传输页的隐式输入；业务状态相互影响 | 页面级 ViewModel；跨库传递必须显式选择文件/产物，不共享全局可变结果 |
| LabOperations 集中调用八类能力 | 不便作为单库接入代码复制参考，继续扩展会成为功能聚合类 | 按库拆 DemoRepository/UseCase；保留真正通用的示例数据和产物辅助 |
| Fragment 仅继承 BaseFragment，VM 继承 AndroidViewModel | 没用上 BaseViewModel 的状态/事件、BaseVM 页面自动绑定 | 使用 BaseVMTitleActivity、BaseVMFragment、BaseViewModel 等已有体系 |
| 普通按钮承担返回，直接构造 AlertDialog | 标题栏、弹窗、列表样式与基础库没有形成一致范例 | 标题栏复用 CommonTitleBar；通用提示走 DialogKit，选择/预览走 XPopup |
| 初始化后可自动生成样例并执行 | 一次按钮成功不能代表完整业务流程；样例与用户输入边界不明显 | 显式选择“示例数据/我的文件”，输入校验后进入参数、执行、结果步骤 |

已有公开依赖修复、定位 debug 可选化仍然保留；问题在 Demo 的业务组织和基础设施使用，不需要推倒独立库实现。

## 基础库的实际复用方式

| 页面职责 | 已有实现 | 使用原则 |
| --- | --- | --- |
| 普通独立业务页 | BaseVMTitleActivity<VB, VM> | 统一标题/返回、系统栏和状态绑定；内容使用 contentBinding，不重复造标题容器 |
| 多步骤子页 | BaseVMFragment<VB, VM> | 每步绑定自己的内容；共享会话仅限该库的流程宿主，不扩散到整个 App |
| 目录与任务列表 | BaseVMListActivity / BaseVMListFragment、BaseRecyclerViewAdapter | 复用列表、空态及内容态；有特殊筛选布局时采用 BaseVMFragment + 现有 Adapter |
| 拍摄/媒体预览 | BaseVMFullScreenActivity | 仅真正需要的预览使用全屏，处理自身退出、系统栏恢复和资源释放 |
| 加载、空态、错误、一次性事件 | BaseViewModel、BaseUiState、BaseUiEvent | Base 状态处理页面公共状态；业务 StateFlow 保存参数、进度、结果，避免重复弹两套 loading |
| 弹窗 | DialogKit、CommonDialog、XPopup | 普通确认复用基础封装；输入/选择/底部操作/图片查看使用已有弹窗能力 |
| 文件/图片选择和访问 | PhotoPickerHelper、DocumentPickerHelper、FileUtil、UriUtil、PermissionHelper | 先核对契约，符合场景则复用；SAF 临时授权和系统 ActivityResult 留在宿主 UI 层 |
| 导航 | core_router_api + annotation/processor/plugin | 为每库注册稳定路由，演示带参、结果返回、降级；BaseUiEvent.Navigate 当前默认不执行导航，必须由宿主显式接到 Router |
| 网络 | core_network | 请求/下载演示使用已有结果和错误封装；局域网服务端仍由 lib_drop 实现，协议上传仍由 lib_upload 实现 |
| 全局初始化 | DevLibApplication | CoreUtils 只初始化一次；业务 VM 通过仓储获得平台能力，避免所有 VM 为持有 Context 都继承 AndroidViewModel |

已有 BaseVM 类型使用 ViewModelProvider 创建 VM；需要构造参数注入时应通过宿主的默认 factory 等受支持入口适配，不能覆盖初始化后绕过 Base 状态绑定。BaseViewModel 当前 LiveData 状态 setter 应在主线程调用，耗时工作完成后切回主线程发布。

继承 Base 不能替代正确生命周期：相机预览由页面释放；LAN/RTC 会话拥有独立关闭策略；离开子步骤和结束整个流程应区别处理。Insets 的消费方只有一个，标题 Activity 和子 Fragment 不重复添加系统栏 padding。

## 工程与页面组织

建议工程职责：

```text
app/                         应用壳、初始化、顶层入口与平台配置
feature/feature_app/          库目录、搜索、分组、接入状态
feature/feature_demo_common/  Demo 专属样例、产物引用、结果组件（按实际复用再提取）
feature/feature_demo_foundation/  Base/Utils/Network/Router/XPopup 独立页面
feature/feature_demo_files/       Drop/HTML/Upload/Transfer/Archive/Share 独立页面
feature/feature_demo_image/       图像/元数据/QR/媒体/规格等独立页面
feature/feature_demo_document/    Document/PDF/ReportPDF 独立页面
feature/feature_demo_account/     三套登录/FCM/Billing/Entitlement 独立页面
feature/feature_demo_media/       Location/两套Camera/Live/Agora 独立页面
core/                        保持独立库职责，不反向依赖 Demo
```

这些是建议归属，尚未创建模块。每个库独立页面不等于必须为每个库增加一个 Gradle feature；先按业务域隔离依赖，每库有自己的 package、XML、VM、流程和路由。common 不变成第二个 Base，也不吸收网络/上传/文件等正式库职责。

首页每项显示库名、业务名称、能力摘要和真实状态：可本地演示、需要配置、需要外部设备、仅构建期验证、默认停用。缺少凭据时展示具体配置缺口，不把不能操作的入口标记为已接入。美颜不出现在可操作列表，DevHub 不注册入口。

普通流程：选择输入 → 编辑参数 → 校验 → 执行/进度/取消 → 专属结果 → 重试/保存/分享。登录、支付和 RTC 按真实会话状态设计，不机械套用文件导出步骤。

## 全模块页面与独立流程清单

以下覆盖当前 44 个保留模块；5 个美颜模块停用。每项是目标设计，不代表已实现。

| 库 | 专属页面/场景 | 独立流程与结果 |
| --- | --- | --- |
| core_base | 页面与列表状态示例 | 普通/标题/列表/全屏入口 → 加载/空/错/重试 → 弹窗/返回/事件，展示真实基类使用方式 |
| core_utils | 文件与工具工作台 | 选择具体工具 → 输入 → 执行 → 可核对结果；图片选择、URI、摘要、存储等分子页，不堆成按钮墙 |
| core_network | API 请求与下载页 | 设置测试服务 → 请求参数 → 执行/取消 → 响应、错误、下载文件；不暴露认证信息 |
| lib_xpopup | 弹窗交互场景 | 选择类型 → 编辑配置 → 展示 → 回调结果；覆盖确认/输入/底部/锚点/图片等 |
| core_router_api | 页面跳转与结果页 | 输入路由/参数 → 跳转 → 目标页验参 → 返回结果；包含拦截/找不到路由 |
| core_router_annotation | 注解契约页 | 独立注解入口 → 查看真实带注解示例 → 打开对应目标页验证注入；不伪装成运行时编译注解 |
| core_router_processor | 代码生成验证页 | 独立入口 → 查看构建生成结果及真实路由样例 → 导航验证；生成本身在 KSP 构建时执行 |
| core_router_gradle_plugin | 插件接入验证页 | 独立入口 → 展示配置、产物与接入样例 → 对照本地构建证据；不提供虚假的手机端 Gradle 执行 |
| lib_drop | 浏览器文件发送 | 选择文件 → 地址/会话配置 → 开启 → 配对审批 → 任务进度 → 停止；明确当前前台会话范围 |
| lib_html | 接收网页资源预览 | 选择页面/语言 → 固定 assets 资源预览 → 交互状态；完整下载联调另显式启动 Drop 会话 |
| lib_upload | 上传服务器与任务 | 选择协议 → 配置服务器/凭据 → 测试连接 → 选择文件 → 上传/取消/重试 → 服务端结果 |
| lib_transfer | 文件管理与持久任务 | 选择源/目标 → 浏览目录 → 复制/移动/冲突决策 → 任务列表 → 暂停/恢复/重试；与单次上传分开 |
| core_archive | 压缩与解压 | 选文件或归档 → 压缩级别/密码等 → 执行 → 内容列表/校验 → 打开输出；覆盖错误密码与取消 |
| core_share | 单/多文件系统分享 | 选文件 → MIME/单多选 → 系统分享 → 返回本页；不能把发起 chooser 当成第三方投递成功 |
| lib_operations | 可取消操作任务 | 选择内置受控任务 → 设置预算 → 执行/取消/失败 → 进度、错误码、统计；验证公共契约 |
| core_image_render | 图片编辑器 | 选图片 → 选择水印/批注/相框/签名等 → 编辑/撤销/预览 → 导出；不同编辑能力有参数子页 |
| phototoolkit-engine | 原生图像处理 | 选图 → 压缩/缩放/合成等 → 参数预览 → 执行 → 前后对比和输出统计；模型能力单列前置检查 |
| phototoolkit-raw | RAW 冲洗 | 选择 RAW → 白平衡/输出参数 → 解码预览 → 导出 → 图像信息 |
| lib_metadata | 元数据查看与编辑 | 选图 → 分组字段 → 编辑/模板/隐私清理 → 写入副本 → 回读差异；原图与产物明确区分 |
| core_media_store | 媒体保存与发布 | 选输入/生成产物 → 内部登记或 SAF/相册输出 → 授权 → 保存 → 查询结果 |
| id-photo-library | 证件规格检索 | 国家/证件/渠道筛选 → 规格详情/证据 → 资料包版本与导入校验；不冒充抠图制作引擎 |
| portrait-model-assets | 模型配送状态 | 检查模型包 → 请求下载 → 进度/失败/重试 → 可用性；普通 APK 未具备 Play 配送时明确受限 |
| core_qrcode | 二维码工作台 | 生成：内容/样式/Logo → 预览 → 回读验证 → 导出；识别：选图/扫码 → 识别结果 |
| core_qr_transfer | Nearby 收发 | 选择发送/接收 → 权限 → 配对/扫码 → 身份确认 → 传输/取消 → 完整性结果；独立于 LAN |
| lib_document | Office 报告编辑与导出 | 新建模板数据 → 段落/表格/图片配置 → DOCX 或 XLSX → 导出 → 结构检查与外部打开；固定字符串只作初始示例 |
| core_pdf | 图片 PDF 编排 | 选多图 → 排序/纸张/方向/边距/网格 → 预览 → 导出 → PDF 结果；不和 Office 导出共用按钮面板 |
| lib_report_pdf | 结构化报告 | 编辑报告资料/章节/照片/签名 → 模板与主题 → 预览 → 导出 → 结果；与图片拼页 PDF 分开 |
| lib_auth_credentials | Credential Manager 登录 | 检查 clientId → 发起账号选择 → 结果/取消/错误 → 当前会话 → 退出；不展示原始 Token |
| lib_auth_google | Google 登录兼容方案 | 独立配置/入口 → 登录 → 账号结果 → 退出/失败重试；不和另一方案共享单个登录状态 |
| lib_auth_firebase | Firebase 会话 | 配置校验 → 获取 Google 凭据 → Firebase 交换 → 会话状态 → 退出；删除账号单独明确确认 |
| lib_push_fcm | 推送注册与接收 | 配置/通知权限 → Token 获取状态 → 消息列表 → 前后台通知/点击结果；无后端时不伪造接收成功 |
| lib_billing_play | 商品与购买恢复 | 配置商品 → 查询 → 商品详情 → 测试购买/取消 → 状态 → 恢复；真实收费步骤单独确认 |
| lib_entitlement | 权益策略演示 | 编辑受控快照/账号/能力/时间 → 判断 → 解释允许/拒绝原因；明确为策略模拟，与真实购买分开 |
| lib_location | 定位与离线地址 | 选择定位模式/离线数据 → 权限 → 获取/回退 → 坐标/地址/错误；高德配置缺口仅影响高德模式 |
| lib_camera | 实时相机 | 权限 → 预览 → 镜头/旋转/帧配置 → 实时帧状态 → 停止释放；不隐式开启美颜 |
| lib_camera_capture | 拍摄与证据信息 | 权限 → 拍摄参数/可选定位 → 拍照 → 预览/重拍 → EXIF/签名 → 保存 |
| lib_live_api | 媒体会话契约 | 选择实现/角色/配置 → 会话 → 事件与状态 → 退出；无实现时只允许明确标记的模拟演示 |
| lib_agora | RTC 房间 | 配置 AppID/Token/频道 → 权限 → 加入 → 本地/远端媒体控制 → 离开；不依赖美颜开启 |
| lib_crash | 内部崩溃演练 | debug 内部入口 → 场景说明 → 明确二次确认 → 主动触发 → 重启检查；不加入自动冒烟或普通操作 |
| lib_beauty | 停用 | 保留源码，无可操作入口 |
| lib_beauty_rfb | 停用 | 保留源码，无可操作入口 |
| lib_beauty_mediapipe | 停用 | 保留源码，无可操作入口 |
| lib_live_beauty | 停用 | 保留源码，无可操作入口 |
| lib_tencent_beauty_kit | 停用 | 保留源码，无可操作入口 |

构建期模块和资源包同样有独立目录入口，但展示符合自身职责的验证流程；不为凑页面数量制造假业务。一个用户流程可以有多个步骤页，一个库也可以有多个能力子页。

## 重构顺序与验收

1. 先建立逐库目录、Base 页面/VM/路由约定，统一标题、空态、错误、弹窗与样例数据机制。
2. 用 core_pdf 完成第一条独立多步骤业务流程，作为页面组织参考；同时做 Base/XPopup/Router 的基础示例验证规范。
3. 逐库拆出 Metadata、ImageRender、QR、Document、ReportPDF、Archive、Share、MediaStore；拆除共享 LabViewModel 对这些状态的持有。
4. 分别落地 Drop/HTML、Upload、Transfer、Nearby；各自处理网络、审批、凭据、冲突与任务生命周期。
5. 再补账号/支付/位置/相机/RTC/原生模型，各入口先呈现真实接入条件，再开放操作。

每库验收：独立入口、专属业务布局、独立状态、可辨认的输入/参数、进行中/取消/失败/重试、专属结果、真实配置边界、接入代码可定位。不能只建一个 XML 或把通用页面换个标题就算完成。

保留此前依赖修复与有价值的测试断言。设备测试按用户要求不执行；后续实现做本地构建、JVM 测试及独立消费验证，未做的视觉/权限/外部服务/多设备验收明确记录。
