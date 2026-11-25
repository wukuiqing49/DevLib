# core_guide V3 设计交接

2026-11-25。用户否定上一版视觉，并明确选择「演示页和引导浮层都重做」。本版已经实现并在 OPPO 截图；用户尚未确认最终视觉。

## 同一设计方向

演示页以一个真实「试一下」控件作为预览，下面用六行分组列表承载测试场景。浮层采用靠近目标的紧凑说明，默认反差面板、轻遮罩、文字操作；减少大按钮、描边和嵌套面板的竞争。标题、正文、步骤和操作有明确层级，不引入玻璃装饰。

原入口全部保留：`start` 分步引导、`longText` 长文本、`colors` 反差样式、`fullscreen` 全屏、`noMotion` 关闭动画、`dialog` 弹窗内引导。新增预览按钮 `target` 启动真实步骤。第二步指向原生返回入口，支持上一步、跳过、完成和系统返回。

库默认最大宽度 280dp、圆角 14dp、标题 16sp、正文 14sp，操作触区至少 48dp。跳过与下一步同处底部，长文正文滚动；空间极小时整卡滚动。默认无三角箭头；指向关系以目标留白、高亮和就近定位表达。宿主可覆写颜色、启用箭头和实心主按钮。

入场/切步/退出为 180/200/120ms，目标强调一次；关闭动画直接到最终状态。窗口系统栏与 Insets 仍由宿主管理，库不改变全屏状态。复用现有 Android Base、XML、ViewBinding 和 MVVM。

## 参考及证据

采用 Apple 设计原则中的上下文提示、简短说明、可跳过、文字可读和克制动效；保留 Android 触区与返回约定。参考 [TapTargetView](https://github.com/KeepSafe/TapTargetView)、[Spotlight](https://github.com/TakuSemba/Spotlight) 的目标聚焦及 [Balloon](https://github.com/skydoves/Balloon/blob/main/docs/migration.md) 的锚点提示思路，没有引入或复制这些库。

- `preview.html` / `preview.png` 是交互概念预览，不能代替原生验收。
- [OPPO 实际页面与浮层](../../testing/evidence/2026-11-25-core-guide-oppo/v3-native-overview.png)。
- [验证记录及未验证项](../../testing/core-guide-v3-2026-11-25.md)。
