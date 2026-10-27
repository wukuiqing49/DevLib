# 本地执行与结果

环境：Windows PowerShell、JDK 17、Gradle 9.3.1、AGP 9.1.1、Kotlin 2.2.10、compile/target API 37、min API 26。完整构建日志保留于根 build，不随 Git 提交。

```powershell
.\gradlew.bat :core:core_document:testDebugUnitTest :core:core_document:assembleDebugAndroidTest :feature:feature_demo_document:testDebugUnitTest :feature:feature_demo_common:testDebugUnitTest :app:assembleDebug :core:core_document:publishReleasePublicationToDevLibLocalRepository --console=plain --no-daemon '-Pkotlin.compiler.execution.strategy=in-process'
.\gradlew.bat -p samples/published-consumer compileDebugKotlin --console=plain --no-daemon '-Pkotlin.compiler.execution.strategy=in-process'
python -B .agents/scripts/validate_android_workflows.py --project-root . --strict-i18n --skip-figma
python -B .agents/scripts/validate_android_source.py --project-root . --report build/record-final-source2.json
python scripts/verify_demo_catalog.py --apk app/build/outputs/apk/debug/app-debug.apk
git diff --check
```

另对 `core/core_document/src/main/java/com/wkq/dev/document/*.kt` 九个文件逐一传入 `--file` 扫描，结果见 core-source.json；不以目录参数的零文件扫描充当证据。

主构建成功：2m36s，624 tasks（31 executed、593 up-to-date）。56 项 JVM 测试结果按当前 JUnit XML 汇总于 summary.json；设备用例仅构建，未执行。

静态结构、严格国际化、UI、构建检查通过；架构两项历史启发式警告保留。APK 19 模块、18 能力入口、5 路由组。未执行设备命令，不修改 CI，不自动提交、推送或推进测试基线。独立消费者最终状态见 summary.json。
