# Google 单库支付验证命令（2026-10-30）

在项目根目录执行；本地 Gradle 使用 JDK 17、SDK 37、minSdk 26，Robolectric 页面用 API 35。以下均最终退出 0；未执行设备命令、真实交易或远端 CI。首次 PowerShell 未引用的 JVM 参数被拆成任务名，改用已引用的项目属性后构建通过，未改业务绕过检查。

```powershell
.\gradlew.bat :core:core_google:testDebugUnitTest :feature:feature_demo_google:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :core:core_google:publishReleasePublicationToDevLibLocalRepository --no-daemon '-Pkotlin.compiler.execution.strategy=in-process'
# 本轮亦执行并通过基础依赖本地发布：
.\gradlew.bat :core:core_base:publishReleasePublicationToDevLibLocalRepository :core:lib_xpopup:publishReleasePublicationToDevLibLocalRepository --no-daemon '-Pkotlin.compiler.execution.strategy=in-process'
.\gradlew.bat -p samples/published-consumer compileDebugKotlin --no-daemon '-Pkotlin.compiler.execution.strategy=in-process'
python -B .agents/scripts/validate_agent_structure.py --project-root .
python -B .agents/scripts/validate_android_workflows.py --project-root . --skip-figma --strict-i18n
python -B .agents/skills/android-ui-workflow/scripts/validate_ui_output.py --module-src core/core_google/src/main/java --module-res core/core_google/src/main/res
python -B .agents/skills/android-i18n-workflow/scripts/validate_i18n_resources.py --res-dir core/core_google/src/main/res --strict-missing
python -B design/billing/validate_locales.py
python -B scripts/verify_demo_catalog.py --apk app/build/outputs/apk/debug/app-debug.apk
git diff --check
```

源码审计：`validate_android_source.py --project-root .` 重复 `--file` 指定 source-audit.json 列出的 7 个文件；结果 0 发现。不是完整历史库审计；Manager 只人工复核本次差异。新增/改动声明中文 KDoc；基础库不批量补注释。

最终构建日志 `build/billing-validation.log`，本地发布日志 `build/billing-publish.log`，消费者日志 `build/billing-consumer.log`；完整日志留在本机，精简可复核计数和源码指纹随 Git 保存。App 和测试 APK 位于各自 build 输出；未安装。

源文件 SHA-256 与 APK SHA-256 见 summary.json；HEAD 只是未提交变更的基础提交，不单独作为本轮通过基线。测试报告/计划维护完成，不推进全功能基线。
