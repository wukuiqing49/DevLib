# LensDock → DevLib 通用 Skill 更新同步

本轮比较两仓库 .agents/skills 全部文件，同步 7 个 Skill、10 个文件，并修正 .agents/INDEX.md 的当前模块数量为 22（功能库 12）。DevLib 的 .agent 是指向 .agents 的现有目录联接，因此从两种路径读取均为本次结果；不新建重复副本。

- ai-video-generation：同步纯英文 Flow 作业包、Prompt 约束及界面/账号阻塞后的降级方法。
- android-i18n-workflow：同步 Android 旧语言代码 in/iw/ji 到 id/he/yi 的标准化，以及现有回归测试。
- android-project-architecture-workflow：同步按实际注册模块与 projectDir 扫描 Manifest 和源码，避免扫描缓存/未注册目录。
- acceptance-review、android-ui-workflow：同步功能入口映射、风格约束与同一设计交接要求，保留 DevLib 的导航选型规则。
- google-play-aso、plan-google-play-listing-assets：复用目标项目已有关键词资料，区分 Google Ads 与 Play Console 报表；移除 LensDock 专属资料链接，不复制其他产品数据。

保留 DevLib 自身的品牌、工作台目录、交互组件、脚手架和架构约束；推广发布 Skill 的 DevLib 版本含更新的授权、身份与发布核验规则，未用 LensDock 较旧版本覆盖。用户原有未提交修改保留。

验证：7 个 Skill quick_validate 均通过（Python UTF-8 模式）；国际化 12 项、架构脚本 12 项、模块布局 3 项测试通过，共 27 项。Agent 结构、配置与实际工程架构静态检查通过，架构保留 34 项既有警告。未运行 Android 构建、设备或发布操作，本轮仅同步 Agent 文件。
