# 库分类、规范与 Demo 对照

当前为 19 个模块，按职责组织；目录前缀和来源项目不代表分类。

| 分类 | 源码目录 | 数量 | App 入口 |
| --- | --- | --- | --- |
| 架构库 | core/architecture | 8 个模块、5 套体系 | 架构，默认首页；路由子模块在体系详情中切换 |
| 功能库 | core/functional | 9 | 功能，分文件与连接、图片、文档 |
| 三方服务库 | core/thirdparty | 1 | 三方：Google 登录、Firebase Auth、Play 支付、官方兑换与评分 |
| 内部支撑 | core/internal | 1 | 不单列；lib_operations 由真实功能流程覆盖 |

App 有 15 个库目录入口、18 个能力路由；三个额外路由来自 Routers 的注解、处理器和构建插件。工作区是宿主功能，不作为独立 core 库统计。图片 PDF 与统一记录导出同属 core_document，不重新拆库。

## 事实与维护入口

- [模块清单](../docs/library-catalog.json)：category / directoryGroup / path 分别记录分类和物理位置，分类键统一为 architecture、functional、thirdparty、internal。
- [源码目录](../core/README.md)：模块职责与接入兼容方式；settings.gradle 的 projectDir 将物理目录映射到既有 `:core:<module>`。
- [Agent 规则](../.agents/rules/library-organization.md)：模块调整时同步清单、配置、文档与 Demo 的要求。规范只维护 `.agents/` 与 `AGENTS.md`，不复制第二套入口。
- [项目档案](../.agents/config/project-profile.yml)：当前工程路径与验证命令。project-scaffold.yml 是新工程生成示例，不能用来覆盖现有源码。
- import-manifest 与旧测试证据保留历史路径；测试报告的新条目注明本轮范围，旧设备结果不代表新页面通过。

## Demo 行为

架构、功能、三方三个 LibraryListFragment 实例各自保存搜索、筛选和滚动状态，快捷入口仅来自当前分类。原 foundation / libraries 保存键继续兼容；旧功能页若保存 Google 分类，进入后重置为全部功能，Google 请到三方页访问。已有路由和独立业务页面不改名，不自动执行登录、上传或购买。

接入详情同时显示分类、真实源码目录、运行条件、配置要求与依赖。源码依赖继续使用旧 Gradle 逻辑模块路径；KSP 和构建插件不当作普通运行时依赖。

复用 BaseVMFragment、BaseVMTitleActivity、BaseViewModel 和 BaseRecyclerViewAdapter，普通页面保留 Edge-to-edge。MainActivity/BaseTitleActivity 负责系统栏、刘海与 IME 避让，BottomNavigation 不重复消费 Insets；不新增全屏策略。标题和统计提供默认中文、zh-rCN、en，独立 Google 页面保持已有语言资源。

构建和静态检查不等于旋转、进程恢复、长文案、RTL、大字体、平板或 Android 17 设备视觉通过。实际验证见 [测试报告](testing/TEST_REPORT.md)。
