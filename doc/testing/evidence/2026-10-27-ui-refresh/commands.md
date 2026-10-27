# 本轮验证命令与边界

- `gradlew.bat :app:assembleDebug :feature:feature_demo_common:testDebugUnitTest --no-daemon --max-workers=2 --console=plain`：通过，27 项单元测试实际执行。
- 最后仅补充主题初始系统栏明暗 bool 后运行 `gradlew.bat :app:assembleDebug --max-workers=2 --console=plain`：通过，38 项执行 / 498 项缓存；没有重复无关库测试。
- `python -B .agents/scripts/validate_android_workflows.py --project-root . --skip-figma --strict-i18n`：通过，构建/UI/i18n 0 警告；架构保留独立消费者及 CoreUtils 文本匹配的 2 项历史提示。
- `validate_android_source.py --file ...`：仅选择任务前快照对比的 42 个 app/feature 文件，0 项发现；中文 KDoc 与业务职责另经人工复核。
- `python -B scripts/verify_demo_catalog.py --apk app/build/outputs/apk/debug/app-debug.apk`：20 模块、19 页面、5 路由组通过；脚本 JSON 的 deviceTesting=not run 只描述脚本自身，不代表本报告未做真机检查。
- 使用指定 Pixel 8 的 adb install -r / am start -W / uiautomator / screencap；未卸载、清数据或操作其他设备。最终安装启动成功，单次冷启动 669ms，不作为性能基准。
- 本轮操作包含中文输入法下搜索关键字提交，NAS 搜索结果 1 项；进入 NAS/Google 仅检查表单，无连接/登录/购买。
- 迭代期间两次 Windows classes.jar 文件锁导致构建失败，停止 Gradle 守护进程后重试成功；一次宿主 R 可见性编译错误及标题重复缩放已修复，最终构建通过。
- 截图按迭代阶段保留：完整流程及布局测试之后，最后补充主题初始系统栏 bool；最终 APK 重新安装并复核深浅首页。源指纹和 APK hash 见 summary.json，不将缓存/先前阶段测试冒充每个 APK 都从头重跑。
- 原始当前进程日志留在 build 临时目录；未匹配到 FATAL EXCEPTION / Fatal signal / ANR in。没有采集全设备日志。
- 未修改基础库源码、CI；未提交、未推送。
