# 本轮本地验证

全部命令从仓库根目录执行，不调用 adb，不创建 CI。

```powershell
.\gradlew.bat :app:assembleDebug :feature:feature_app:testDebugUnitTest --no-daemon --console=plain '-Pkotlin.compiler.execution.strategy=in-process'
python -B .agents/scripts/validate_android_workflows.py --project-root . --skip-figma --strict-i18n
python -B scripts/test_core_layout.py -q
python -B scripts/test_verify_demo_catalog.py -q
python -B .agents/scripts/tests/test_validate_agent_structure.py -q
python -B .agents/scripts/tests/test_validate_android_workflows.py -q
python -B scripts/verify_demo_catalog.py --apk app/build/outputs/apk/debug/app-debug.apk
python -B .agents/scripts/validate_agent_structure.py --project-root .
git diff --check
```

上述均通过；Gradle 为 542 个任务，目录 JVM 测试 6 项，Python 测试合计 34 项。架构静态检查保留 2 个既有提示，UI 与严格国际化检查无警告。设备和真实外部服务未执行。

源码审计使用 `validate_android_source.py --project-root . --file <文件>`，显式选择 LibrarySection、DemoCatalog、DirectoryPolicy、CatalogFragment、LibraryListFragment、LibraryDetailActivity 和 DirectoryPolicyTest 七个 Kotlin 文件，结果为 0 发现；完整记录见 source-audit.json。该选择不扫描未改动引擎或基础库历史声明。

本地构建和聚合日志为 `.ai-work/library-navigation-build.log`、`.ai-work/library-navigation-static.log`，不纳入 Git。APK 与源码摘要见 summary.json / source-files.json；测试使用未提交工作区，不以 HEAD 代替当前源码指纹。
