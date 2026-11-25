# core_guide

独立 Android View 功能引导库。包名 `com.wkq.guide`，主要入口 `FeatureGuide`。不绑定任何业务产品，不依赖 Base、Utils 或 XPopup，不申请悬浮窗权限。

## 构建与接入

- minSdk 26，compileSdk 37，JDK 17；演示 App targetSdk 37。AAR 不替宿主设置 targetSdk。
- 源码依赖：`implementation project(':core:core_guide')`。
- 本地 Maven：`implementation 'com.wkq.dev:core-guide:0.1.0'`。版本由本模块 `version.properties` 维护，由 `:core:core_guide:publishReleasePublicationToDevLibLocalRepository` 生成到根 `build/repo`。
- AndroidX Core、Lifecycle、Activity 为唯一运行依赖，调用方无需继承本仓 Base；演示页复用工作台 Base/MVVM。

### JitPack 远程依赖

在消费项目 `settings.gradle` 的 `dependencyResolutionManagement.repositories` 添加：

```groovy
maven {
    url = uri('https://jitpack.io')
    content { includeGroup('com.github.wukuiqing49.DevLib') }
}
```

模块依赖：

```groovy
implementation 'com.github.wukuiqing49.DevLib:core-guide:guide-v0.1.0'
```

JitPack 标签 `guide-v0.1.0` 对应本模块 0.1.0；不要使用聚合依赖 `com.github.wukuiqing49:DevLib`，避免带入其他库。远程构建和实际消费状态见 [发布记录](../../../doc/testing/core-guide-jitpack-2026-11-25.md)。库不要求宿主联网权限；JitPack 只是构建时下载 AAR 的仓库。

```kotlin
val guide = FeatureGuide.show(
    window = requireActivity().window,
    owner = viewLifecycleOwner,
    steps = listOf(
        GuideStep(
            id = "create_item",
            target = { binding.createButton },
            title = getString(R.string.create_guide_title),
            message = getString(R.string.create_guide_message),
        )
    ),
    options = GuideOptions(
        onStep = { id, index -> /* 当前步骤实际展示后调用 */ },
        onFinished = { result -> /* 阅读完成、跳过、销毁等分别处理 */ },
    ),
    backDispatcher = requireActivity().onBackPressedDispatcher,
)
// guide.next() / previous() / skip() / dismiss()；所有会话 API 仅限主线程。
```

同一容器只允许一个引导，新引导替换旧引导，结束结果为 `REPLACED`。传入 Fragment 的 `viewLifecycleOwner`，不要使用长于页面视图生命周期的对象。返回处理由显式 `backDispatcher` 接入；不传时由宿主处理返回或调用 `dismiss()`。

## 高亮、文字和主题

- `HighlightShape.CIRCLE` / `ROUNDED_RECTANGLE`，可配置留白与圆角；目标保持原始外观。
- `GuideStyle` 配置最大宽度、边距、字号、行距和卡片圆角。主次按钮正常横排，长译文按实际宽度纵排；跳过位于底部操作区。正文不截断，保留 `\n` 分段并自然换行；正文可滚动，极小窗口或大字体下整卡改为滚动，所有操作仍可到达。
- `GuideColors` 包含遮罩、卡片、标题、正文、主色、主按钮文字、次要文字和高亮，可显式覆盖。默认 `appearance = GuideAppearance.CONTRAST` 根据宿主背景选择反差明暗面板；`HOST` 跟随宿主 `colorSurface` / `colorBackground`。默认遮罩为 20% 黑色。
- `GuideStyle.showArrow` 控制小箭头，默认关闭；降级重叠和自定义卡片时不画误导箭头。默认最大宽度 280dp、圆角 14dp、标题 16sp、正文 14sp，样式可覆盖。
- 默认主色读取宿主 `colorPrimary`，普通系统主题回退到 `colorAccent`，无需强制 Material 或 AppCompat。默认主操作采用文字样式并调整到可读对比度；设置 `filledPrimaryAction = true` 可启用实心按钮。
- 可在单步 `style` 覆盖全局样式。动态主题切换后由宿主重建或重新展示，不能把旧 Context 当新主题使用。
- 默认英文和简体中文；其他语言回退英文，宿主可覆写 `guide_*` 资源。文案接受 CharSequence，不硬编码产品名称。
- 自定义 `customContent = { actions -> yourView }`，必须返回未挂载 View；自定义布局负责自身文本、滚动和无障碍。

## 动画

`GuideMotion` 可设置入场、切步、退出时长；默认 180/200/120ms。卡片淡入轻移、目标跨步骤插值移动；`NONE`、`PULSE`、`RIPPLE` 控制高亮强调，默认一次，可配置 0～10 次。系统关闭动画或 `enabled = false` 时直接显示最终状态。不改变真实目标的缩放和透明度。

快速切换会取消旧动画。背景化隐藏引导并暂停目标等待，恢复后重新定位；销毁立即释放所有监听和返回拦截。`onFinished` 仅派发一次。

## Android 15 / API 35 到 target 37 的窗口契约

**窗口状态属于宿主，库从不修改系统栏可见性、图标明暗、导航栏对比度、cutout 模式或宿主 padding。**

| 场景 | 接入方式与责任 |
| --- | --- |
| 普通边到边页面 | `show(activity.window, owner, ...)`；宿主启用边到边，库在 decor 内绘制覆盖层 |
| 全屏沉浸式页面 | 相同入口；宿主使用 WindowInsetsControllerCompat 隐藏/恢复系统栏，库继承当前窗口模式 |
| Dialog / 全屏 Dialog | 使用 `dialog.window`，目标须在该 Dialog 内；先 show Dialog 再展示引导 |
| PopupWindow | 使用其内容中的专用 FrameLayout 覆盖容器调用 `showIn`，不借用 Activity Window |
| 自定义覆盖容器 | `showIn` 接收 FrameLayout 等支持叠放的容器，容器布局由宿主负责；不要直接传 LinearLayout/RecyclerView |

库基于真实目标可见区域和覆盖层坐标定位，不猜状态栏高度，不使用过时 `SYSTEM_UI_FLAG_*`。提示的安全区域从可见 `systemBars`、`displayCutout`、`ime`、`mandatorySystemGestures` 取各边最大值，再与当前覆盖容器相交；不会把 IME 和导航栏相加。桌面 caption bar 属于 systemBars。隐藏状态栏不会固定留一条空白，临时唤出系统栏后重新计算。

目标需要在同一窗口、已挂载且可见。列表先由宿主滚动到目标，再交给库定位；库不会猜测列表位置或自动触发业务展开。目标缺失先隐藏提示，默认等待 3 秒，到期结束为 `TARGET_UNAVAILABLE`，可配置等待上限。超长内容或窗口空间不足时采用安全区底部说明面板，可能覆盖部分目标，优先保全说明和退出操作。

窗口旋转导致宿主重建时，旧会话结束 `DESTROYED`；需要续读的宿主保存步骤 ID 并用新 View 重建会话，库不保存旧 View、相机状态或业务草稿。当前版本提供同一窗口内的步骤序列，不自动跨 Activity 续接。

官方依据：[边到边](https://developer.android.com/develop/ui/views/layout/edge-to-edge)、[沉浸式](https://developer.android.com/develop/ui/views/layout/immersive)、[Android 16 边到边退出限制](https://developer.android.com/about/versions/16/behavior-changes-16)、[Android 17 目标行为](https://developer.android.com/about/versions/17/behavior-changes-17)。

## 点击与可访问性

遮罩默认拦截底层操作，不隐式触发目标。`TargetAction.NEXT` 点击目标区域后继续；`CLICK_AND_NEXT` 对可点击、启用的目标执行一次 `performClick()` 后继续，适用于普通点击，不支持长按、拖动或手势透传。自定义业务完成条件应保持 `NONE`，由宿主实际结果调用 `guide.next()`。

默认卡片提供目标操作的可访问按钮，标题标记为 heading，背景子树临时对读屏隐藏并在结束后恢复。建议长介绍采用显式下一步；跳过与完成不同。自定义内容需提供同等的关闭、操作和读屏入口。

## 可选记录

`GuideHistory(context, namespace)` 提供按 ID + 版本隔离的本地记录：`SHOWN`、`SKIPPED`、`READ`、`BUSINESS_COMPLETED`。库不自动写入，宿主在 `onStep`、`onFinished` 或真实业务成功时调用 `mark`；`records` 和 `reset` 用于是否展示及重新查看。每个 namespace 建议复用一个实例，避免多个实例并发更新同一记录。记录不包含密码或业务内容。

## 演示与验证

工作台「功能 → 文件类目录」的 `core_guide` 页面提供预览按钮和分步、长文、反差样式、全屏、关闭动画、独立 Dialog 六个入口。分类沿用现有工作台功能域，不修改全局导航。

```powershell
.\gradlew.bat :core:core_guide:testDebugUnitTest :core:core_guide:assembleRelease :app:assembleDebug
.\gradlew.bat :core:core_guide:publishReleasePublicationToDevLibLocalRepository
```

最新测试及未验证场景见 [V3 验证记录](../../../doc/testing/core-guide-v3-2026-11-25.md)。Robolectric 是 JVM 平台模拟，不能替代 Android 35/37 真机的全屏、手势导航、读屏及视觉验收。
