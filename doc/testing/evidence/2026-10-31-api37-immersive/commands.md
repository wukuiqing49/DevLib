# 复核命令与边界

```powershell
.\gradlew.bat assembleLibraries testLibraries :app:assembleDebug --no-daemon --console=plain
python -B .agents/scripts/validate_android_workflows.py --project-root . --skip-figma --strict-i18n
python -B scripts/test_core_layout.py -v
python -B .agents/scripts/validate_agent_structure.py --project-root .
git diff --check
```

最终聚合构建退出码 0；所有 19 个模块的产物与 JUnit 汇总见 summary.json。up-to-date 测试复用 Gradle 对未变输入的结果，不将其计为全部重新执行；NO-SOURCE 不算测试通过。新增回归为 Base 四项与 XPopup 一项，XPopup 原五项也重新执行。

整文件中文注释/Base/资源化审计与 HEAD 比较无新增发现，121 项未改动声明的既有缺口不在本轮批量重写。人工复核本轮修改的方法、Insets owner、公开 API、低版本分支与焦点恢复；单智能体自查，不称独立审查。聚合门禁覆盖 app 与 8 个 feature，保留两个既有架构提示。

初次测试编译修正了 Robolectric getApplication 的泛型误用；中间复测遇到 Windows classes.jar 占用，停止 Gradle 守护进程后 --no-daemon 最终完整通过。既有 AGP 过渡配置、Kotlin 注解及旧兼容 API 的弃用提示仍存在，不宣称零编译警告。

API 35 Robolectric 与纯 JVM 测试均不证明 Android 17 实机行为；未执行 adb、设备、截图、release R8、生产服务器、Google Play 上传或 DropLAN 消费更新。框架兼容源码已落地，运行/视觉和外部产物验收未完成。原测试基线不推进。
