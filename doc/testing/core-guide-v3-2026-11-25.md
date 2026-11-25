# core_guide V3 验证记录

日期：2026-11-25。范围：DevLib 演示页与引导浮层一起重做。此前 V2 视觉被用户否定，本记录对应 V3 当前源码和安装包；尚不代表用户视觉认可。

## 实际验证

| 项目 | 结果及证据 |
| --- | --- |
| 库单元测试 | 42 项通过：FeatureGuide 38 项（19 个方法 × API 26/35 Robolectric），定位算法 4 项。JVM 模拟不等于真机。 |
| Debug / Release | Debug APK、Release AAR、本地 Maven 发布通过；无远端发布。 |
| lintDebug | 0 错误；GuideHistory 有 2 项既有 UseKtx 建议。 |
| 资源及源码 | 本轮源码审计 11 文件、0 findings；新增/修改引导文案 EN/简中检查 0 warnings。全工程仍有原演示模块 93 个简中缺译。 |
| 独立消费 | samples/guide-consumer 离线 compileDebugKotlin + verifyGuideArtifact 通过；实际解析最新本地 AAR，SHA-256 de36cedcbbeaae51ae94b94d338b9a020d01c3468ac011867aff43883474cd4b。 |
| OPPO 设备 | 按用户「运行oppo 测试」授权，仅 PCAM00 / 5ef48cf2，Android 11 API 30、1080×2340、density 480、fontScale 1.0。最新 APK 保留数据覆盖安装。 |
| 页面及分步 | 六入口仍在；预览按钮定位、下一步、上一步、跳过、完成及返回入口已操作。 |
| 长文 | 中文分段与连续英文换行；滑动正文后尾段可见，完成按钮可达，已完成退出。 |
| 全屏 | 隐藏系统栏后展示、退出后恢复；普通顶栏 y=96、全屏 y=0，有 XML/截图。 |
| 弹窗 | 用 Dialog 自己的 Window 定位，目标及说明在弹窗内，完成后可关闭真实弹窗。 |
| 样式/动画开关 | HOST 样式与默认反差样式均展示；关闭动画入口能展示和退出。不宣称帧率、录像或性能验证。 |

## 可复核证据

- [OPPO 页面与提示合图](evidence/2026-11-25-core-guide-oppo/v3-native-overview.png)
- [第二步](evidence/2026-11-25-core-guide-oppo/v3-final-step-two.png)
- [长文滚动后](evidence/2026-11-25-core-guide-oppo/v3-final-long-scrolled.png)
- [全屏](evidence/2026-11-25-core-guide-oppo/v3-final-fullscreen.png) / [恢复](evidence/2026-11-25-core-guide-oppo/v3-final-restored.png)
- [Dialog](evidence/2026-11-25-core-guide-oppo/v3-final-dialog.png)
- [宿主样式](evidence/2026-11-25-core-guide-oppo/v3-final-host-style.png) / [关闭动画](evidence/2026-11-25-core-guide-oppo/v3-final-no-motion.png)
- 构建、消费、JUnit、lint、源码与文案日志存于 `evidence/2026-11-25-core-guide-v3/`。截图同名 XML 提供控件与坐标依据。

命令：`gradlew --no-daemon --offline :core:core_guide:testDebugUnitTest :core:core_guide:lintDebug :app:assembleDebug`；最终 Demo 调整后再次 `:app:assembleDebug :core:core_guide:assembleRelease :core:core_guide:publishReleasePublicationToDevLibLocalRepository`。独立消费者：`gradlew --no-daemon --offline -p samples/guide-consumer compileDebugKotlin verifyGuideArtifact`。

## 限制

未验证 API 35/37 真机强制边到边、读屏、真实 RTL/超大字体、平板与横屏、多窗口、键盘和刘海组合；不将 API 37 编译或 API 35 Robolectric 当作这些结果。OPPO 未改变字体/旋转/主题设置，最终停留正常演示页。没有操作 Pixel，没有提交、推送、远端发布或 CI 变更。完整设备矩阵未完成，不推进全工程自动回归基线。
