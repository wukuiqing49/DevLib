# DevLib 工作台设计基线

## 范围与状态

本基线从现有代码提取，仅适用于 DevLib 工作台及 Demo，不约束消费产品品牌，也不证明现有页面已获用户认可或完成视觉验收。源码是数值事实源；修改对应资源后同步核对本索引，不复制整套颜色数值。

目标风格见 [ui-style.md](../rules/ui-style.md)，本文件只记录实际资源与认可状态，不维护另一套规范。区分设计目标、源码现状、用户认可参考和实机证据；新增认可参考记录反馈日期/任务、版本、页面状态、主题、视口及截图，资源更新不自动推进验收。消费项目建立自己的基线。

## 资源与组件事实源

| 用途 | 位置与标识 |
| --- | --- |
| 主题 | `app/src/main/res/values/themes.xml`：`Theme.DevLib`，继承 Material3 DayNight NoActionBar |
| 明暗颜色 | `feature/feature_res/src/main/res/values/colors.xml` 与 `values-night/colors.xml` |
| 背景/正文 | `feature_res_surface`、`feature_res_on_surface` |
| 主操作/容器 | `dev_primary`、`dev_on_primary`、`dev_primary_container` 及对应内容色 |
| 辅助/错误 | `dev_on_surface_variant`、`dev_secondary`、`dev_error` 及对应容器/内容色 |
| 卡片/边框 | `dev_surface_card`、`dev_outline_variant` |
| 基础间距 | `feature/feature_res/src/main/res/values/dimens.xml`：small/medium/large 为 8/16/24dp；不代表所有布局已统一 |
| Demo 组件 | `feature/feature_demo_common/src/main/res/values/styles.xml`：`Demo.Page`、`Demo.Label`、`Demo.Heading`、`Demo.Button`、`Demo.Button.Primary`、`Demo.Button.Danger`、`Demo.TextInputLayout` |
| 工作台组件 | `feature/feature_app/src/main/res/values/styles.xml`，按实际样式属性复用 |

文字优先使用主题的 `textAppearanceTitleMedium`、`textAppearanceBodyMedium`、`textAppearanceBodySmall` 等语义层级。`FeatureRes_TextAppearance_Title` 当前为空样式，不应作为完整标题规格。Demo 次级按钮为描边样式，Primary 为填充；主题默认按钮是 Primary，页面实现须明确主次，不能将所有按钮自动强调。

## 可参考结构与待验证项

- `feature/feature_app/src/main/res/layout/fragment_catalog.xml`：内容容器与底部导航结构；运行期 Insets 仍需查对应代码。
- `feature/feature_app/src/main/res/layout/item_demo_entry.xml`：模块信息、标题、简介及接入/体验操作；当前卡片圆角 16dp、内边距 12dp，是现状，不是全项目强制值。
- 当前没有在本轮获得用户认可的页面截图；上述页面只作结构参考。图标风格、完整字号效果、字体缩放、主题对比度与系统栏效果仍需实际画面验证。

## 设计方向与更新

工作台建议优先突出能力发现、接入说明和操作结果，减少干扰信息检索的装饰；这是设计建议，具体页面以用户目标为准。复用并不意味着保留已证实的问题：调整共享样式前评估受影响页面，局部需求优先使用已有变体。

消费项目建立自己的同类基线，记录品牌、真实资源和已认可的参考；不能复制 DevLib 包名、颜色或导航作为默认产品需求。
