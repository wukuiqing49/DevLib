# 三入口重整验证

使用项目架构、UI、国际化、构建和功能测试 Skills；基础库实现无差异。

```powershell
.\gradlew.bat :feature:feature_app:testDebugUnitTest :feature:feature_demo_common:testDebugUnitTest :feature:feature_demo_document:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --console=plain --no-daemon '-Pkotlin.compiler.execution.strategy=in-process'
python -B .agents/scripts/validate_android_source.py --project-root . --report build/navigation-final-source.json
python -B .agents/scripts/validate_android_workflows.py --project-root . --strict-i18n --skip-figma
python scripts/verify_demo_catalog.py --apk app/build/outputs/apk/debug/app-debug.apk
git diff --check
```

最后源代码调整后构建成功：55s，582 tasks；36 项 JVM 通过，未变化测试允许 UP-TO-DATE。App AndroidTest APK 仅构建，未执行 instrumentation。

Pixel 8 按本会话用户明确的设备部署授权执行 `adb -s <Pixel8> install -r`、`am force-stop/start -W`、`uiautomator dump`、本地导航点击及 `screencap`。每条设备命令指定同一 Pixel 8，不操作另外的设备。Android 16/API 36，最终 APK 冷启动 711ms。未清数据、卸载、删除用户文件、改变设备偏好或调用真实服务；导出到设备的临时 XML/PNG 已移除。

核对：三个入口、基础组件 5 体系/8 模块、搜索切换保留、路由详情四模块与 KSP 指引、工作区文件缩略图及内部历史页。最初连续操作遇到系统过渡动画、未清除搜索条件和 IME 转写，导致自动断言不成立；确认焦点、等待 UI dump 后按实际输入与筛选状态重查通过，不以最初误操作证明功能失败或通过。输入法/偏好未修改。

截图总览只含库目录及接入页。工作区已有文件与完整 UI dump 留于本地 `build/functional-test/navigation/`，避免把用户资料带入 Git。目录脚本结果中的 deviceTesting=not run 仅表示该脚本不执行设备测试；本轮定向设备结果单独见 summary.json。

全日志：`build/navigation-final-build2.log`、`build/navigation-final-static2.log`。不推进全功能基线，不修改 CI。提交和推送按用户本轮明确要求执行。
