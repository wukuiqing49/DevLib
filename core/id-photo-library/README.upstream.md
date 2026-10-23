# 证件照规则数据库

`id-photo-library` 是商用证件照规则的数据模块。它不负责图像处理，也不把当前
`phototoolkit-engine` 的 JSON 内容导入数据库；现有 JSON 只用于识别需要承载的业务字段。

## 模块边界

- `phototoolkit-engine`：人像处理、构图计算和输出渲染。
- `id-photo-library`：国家、具体证件、申请渠道、输出规格、构图规则、证据和国际化。
- `feature_app`：国家 -> 证件类型 -> 具体证件/渠道 -> 输出规格页面。

页面在官方规则数据达到可用覆盖前继续使用现有数据源，避免空库或未核验规则影响当前功能。

## Schema 分层

数据库 V2 共 38 张表。V1 的 23 张表保持兼容，继续承载发布版本、国家/地区、证件项目、
申请渠道、输出规格、基础构图、要求文本、来源证据和国际化内容。V2 新增 15 张表：

- 签发与地域：`issuing_authority`、`issuing_authority_text`、`jurisdiction_relation`。
- 适用范围：`rule_scope`、`submission_artifact`、`variant_capability`。
- 输出与构图：`output_encoding`、`composition_constraint`、`background_constraint`、
  `artifact_constraint`、`transform_policy`。
- 证据审计：`source_evidence_assessment`、`program_source_link`、`rule_source_link`、
  `coverage_source_link`。

`composition_constraint` 使用通用的“测量项 + 参考坐标系 + 目标值/范围 + 单位”结构，
可以同时表达毫米、像素和比例规则，不把某个国家当前使用的固定构图字段写死到 Schema 中。
`variant_capability` 和 `transform_policy` 分别声明某一输出是否可生成，以及裁剪、缩放、
抠图、换背景等操作是否允许，避免“有尺寸”被错误解释成“应用可以合规生成”。

## 数据生产边界

Room 数据库是安装到手机上的已发布规则库，不是规则采集和编审后台。完整链路应分为：

```text
官方来源采集 -> 人工核验/字段级证据绑定 -> 版本发布与签名 -> App 下载校验 -> Room 原子切换
```

服务端负责网页和文件归档、差异审阅、继承/覆盖解析、翻译编审、内容哈希、数字签名和覆盖率
统计。Android 端只保存解析后的自包含快照，按 `release_id` 原子安装、切换和回滚；运行时不抓取
政府网页，也不自行把第三方资料升级为 `verified`。

`feature_app` 的证件照页面通过桥接层优先读取 Room 当前激活的 `verified` release。只有明确标记
为 `generatable`，并同时具备输出格式、背景、构图和来源证据的规格才会转换为生成引擎参数；
数据库尚未安装规则包、读取失败或激活包没有完整可生成规格时，页面回退到 bundled JSON。
这个 fallback 仅用于迁移期间保持现有功能可用，不代表 JSON 数据已升级为数据库 verified 数据。

证据只有在来源等级为官方、采用直接访问、HTTP 状态有效、具有核验时间和内容哈希时，才允许
标记为 `verified` 或 `channel_verified`。代理读取、搜索摘要和第三方尺寸网站只能作为发现线索。

## 国际化

固定 UI 文案仍使用 Android `strings.xml`。数据库动态内容分别存储在：

- `jurisdiction_name`
- `document_program_text`
- `application_channel_text`
- `output_variant_text`
- `requirement_text`
- `source_evidence_translation`

`locale_tag` 使用 BCP-47，例如 `en`、`zh-Hans`、`zh-Hant`、`fr`。运行时回退顺序为：

```text
精确 locale -> script -> language -> en -> 稳定业务代码或官方原文
```

官方证据的 `source_language`、`original_title` 和 `original_quote` 永不被译文覆盖。
译文使用 `translation_status` 区分 `human_verified`、`machine_translated` 和 `pending`；
机器翻译不能单独用于把规则标记为 `verified`。

`locale_pack` 记录每种语言包的版本、状态、内容哈希和签名密钥标识。规则包校验会拒绝没有
对应 `locale_pack` 的译文，便于后续按语言发布并审计翻译覆盖。

## 发布和回滚

所有业务表都包含 `release_id`。规则包完整写入后，事务内更新 `catalog_state.active_release_id`；
旧 release 保留，可立即回滚。数据库启用 `exportSchema=true`，后续版本必须提交 Room Migration，
禁止使用 `fallbackToDestructiveMigration`。

`installVerifiedPackage` 只接收已经由上层更新组件完成 HTTPS、内容哈希、数字签名和 Schema
版本校验的数据。当前模块只做结构与 BCP-47 校验，网络下载和密码学验签尚未实现。
