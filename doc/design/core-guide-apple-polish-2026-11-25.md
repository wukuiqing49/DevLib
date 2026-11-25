# core_guide 展示优化交接

用户要求：参考开源项目和 Apple Skill，美化通用引导库展示；沿用既有业务 API、全屏/Insets 责任与 OPPO 授权。

## 依据与取舍

- TapTargetView 官方 README 的目标强调和分步发现：https://github.com/KeepSafe/TapTargetView 。
- Spotlight 官方 README 的独立高亮形状和自定义说明层：https://github.com/TakuSemba/Spotlight 。
- Balloon 官方迁移示例的紧凑提示、箭头及颜色配置：https://github.com/skydoves/Balloon/blob/main/docs/migration.md 。只参考展示，不引入依赖或复制源码。
- 已读取 Apple Skill 的 accessibility、layout、typography、color、designing-for-ios、buttons、onboarding、motion、popovers 与跨平台映射。应用信息层级、邻近目标、可跳过、主次操作、字体适配与可取消动效原则；Android 保留 48dp 触控区、系统字体和原生返回，不移植 Apple 平台专属行为或材质。
- 原文短引：onboarding.md › Best practices："Teach through interactivity."；popovers.md › Best practices："Avoid making a popover too big."；motion.md › Providing feedback："Let people cancel motion."；layout.md › Adaptability："Be prepared for text-size changes."。

## 实机发现

原卡片把步骤、标题、正文及所有按钮纵排，短提示占据过多高度；主按钮整行大胶囊与跳过的强调相近。第二步对宽文本使用圆形，目标范围过大。原实现已经具备真实目标、明暗配色、窗口边界与阅读退出契约，继续保留。

## 同一方案

1. 标题与步骤组成头部信息组，跳过移至头部末端；不增加关闭与跳过的重复入口。
2. 正文独立，自动换行、保留段落。底部正常横排上一步和下一步，下一步是唯一填色主操作。
3. 卡片默认最大宽度 320dp、圆角 20dp，主按钮圆角 12dp；仍可由宿主覆写。中性色面板、细描边与低阴影，遮罩由 70% 降为 60%，保留上下文。
4. 新增可关闭的 showArrow，小箭头表达卡片与目标关系；降级重叠、自定义布局时不画误导箭头。高亮区域保持真实目标外观。
5. 导航按真实文字宽度横纵排；标题与跳过在空间不足时纵排；极小窗口整卡滚动，所有操作保留。没有通过省略、缩字或隐藏能力适配。
6. 默认按钮自带明确按压涟漪，避免不同宿主主题强制变成超大胶囊。动效开关、有限次数和系统设置继续有效。
7. Demo 原有六个入口全部保留；第二步宽文本改圆角矩形。Demo 记录不含用户数据的步骤/结束枚举，区分真实操作与测试读取旧快照。

## 验收

- 编译、库 JVM 和 lint；检查导航横纵排、长文、对比度及销毁恢复。
- OPPO PCAM00 / Android 11：查看短文、第二步、长文滚动、自定义暗面板、无动画、全屏/恢复、独立 Dialog；实际截图单独记录。
- API 35/37 设备、全量语言、TalkBack 和性能指标未测不宣称通过。原有 API 26/35 Robolectric 作为模拟证据。
