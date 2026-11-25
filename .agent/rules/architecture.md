# Android 架构规则

## 模块边界

- `core/` 和 `feature/` 是目录容器，不直接注册为 Gradle module。
- 实际模块使用 `:core:<module>` 和 `:feature:<module>`。
- DevLib 的 core 物理目录按 architecture、functional、thirdparty、internal 分类；settings 的显式 projectDir 映射保留逻辑模块路径，检查模块位置时以映射为准。
- 本仓库分类与 Demo 同步细则见 `library-organization.md`；现有四类角色不得与来源或功能域混为一层。
- `app` 只负责应用壳、全局初始化和顶层导航，不承载大量业务页面。
- `core` 子模块不得依赖 `app` 或任何 `feature` 模块，包括 `feature_res`。独立库自有资源留在本库，宿主共享资源放在 feature_res；不得为复用宿主资源破坏库的独立接入。
- feature 模块不得形成循环依赖；`feature_res` 不依赖功能模块。
- 跨功能逻辑先区分宿主共享编排与独立能力：工作台选择、操作记录、页面结果传递等留在职责匹配的共享 feature；脱离宿主仍可独立接入的引擎/API 才放 core。宿主共享资源放 feature_res，库资源留在本库。
- 功能专属 UI、状态和编排留在对应 feature 模块。

## 复用优先级

1. 目标项目已有共享模块、基础封装和公共组件。
2. AndroidCoreBase、AndroidCoreUtils、AndroidCoreRouters、AndroidCoreNetwork、XPopup。
3. AndroidX 或已有第三方依赖。
4. 确认前三层不能满足后，才新增本地通用能力或依赖。

## WKQ 能力

- 基础库按职责和实际需求组合使用；Base 继承与组件复用要求不意味着必须安装全部 WKQ 库。普通项目无需为了统一技术栈额外接入网络、路由或无关功能库。
- Activity、Fragment、ViewModel、Adapter 按项目约定继承 AndroidCoreBase 对应基类。
- AndroidCoreUtils 的 Kotlin `object` 和扩展函数通过组合或直接调用使用，不要求业务类继承。
- AndroidCoreUtils 只由 app 壳的 `Application` 统一初始化一次。
- 包装模块向下游暴露 WKQ 类型时使用 `api`；完全隔离第三方类型时使用 `implementation`。
- 接入或更新 WKQ 依赖前查询对应 GitHub 仓库最新 tag，并检查与当前构建基线的兼容性。

## 导航与路由选型

- 普通项目默认沿用简单导航：现有 Intent/Activity Result、Fragment 导航或已接入的导航组件即可；不默认增加 AndroidCoreRouters、路由注解、处理器、插件或空 Navigator 包装。
- 大型项目存在跨业务模块解耦、多团队协作、统一拦截、服务发现等实际需求时评估 AndroidCoreRouters，记录收益、依赖成本和迁移范围。页面数或模块数只是线索，不作为自动接入阈值。
- 已有项目优先保持导航契约；DevLib 工作台已采用 Router，局部页面任务继续复用。新增项目不照搬工作台的路由体系，也不因本规则擅自移除已有路由。
- 系统外部入口和 DeepLink 按实际需求处理，不单独构成引入整套路由框架的理由。返回栈、参数/结果、取消、状态恢复须有明确责任方，无论采用哪种导航方式。
- 独立功能库不因 Demo 的导航选型而依赖宿主 Router；路由注册和页面编排放在适用宿主层，保持 core 不依赖 app/feature 的边界。

## 通用能力归属

- 网络、数据库、上传下载、图片、位置、计费、权限、文件、路由、存储和弹窗能力不得在功能模块重复实现。
- 网络安全 XML 和 FileProvider 的 Manifest 声明属于 app 壳平台配置。现有生成器将对应 XML 放在 app；先保留可运行的生成基线，业务共享资源放 `feature_res`，不得同时在两处维护冲突定义。
- 模块归属不明确时，先检查 `settings.gradle`、相关模块构建文件和相邻代码。

## DropLAN 历史专项能力归属（仅明确指定该产品时适用）

- WKQ 接入是生成器候选配置，不是当前已验证框架；复用前检查所需 API、License、线程与运行期离线行为。
- 客户端 Retrofit/OkHttp 下载封装不能直接当成本地 HTTP 服务端、Range 处理或热点控制实现。
- P0 使用最小边界验证热点、地址、鉴权、文件流与测速；不强制引入 Room/WorkManager，不提前创建空业务模块。
- 实际建模块时分离连接、HTTP 服务、调度、文件源和测速推荐职责；对应业务边界以 `.agents/rules/droplan.md` 为准。

- 共享模块 `:core:lib_drop` 承载协议、文件源和传输能力，`:core:lib_html` 承载离线 HTML/CSS/JS 与静态资源映射；两库互不依赖，由 `:feature:feature_app` 编排。
