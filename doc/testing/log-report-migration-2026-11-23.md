# 日志库迁移验收 · 2026-11-23

## 范围与实现

- 日志库从 LensDock/core/core_log_report 移入 DevLib/core/functional/core_log_report；57 个源码、资源、测试与工具文件逐字节核对一致。来源提交及原始文件 SHA-256 存于 docs/import-manifest.json。删除来源目录前再次检查来源与快照无差异。
- 保留 com.wkq.logreport、公开接口、minSdk 26、BaseActivity/BaseViewModel/Adapter、英文及简体中文资源、日夜主题、Manifest 中的非导出页面及 applicationId FileProvider。
- DevLib 添加独立 Maven 发布配置、consumer rules、清单/档案、工作台文件域入口、初始化及 ALog.reportSink。核心库不反向依赖 Utils/宿主/相机/Router。
- LensDock 移除原源码模块注册与目录，使用 com.wkq.dev:core-log-report:0.1.0-SNAPSHOT；显式排除同包名 core-base，继续复用本地 Base/XPopup。仓库由 devlibRepository 配置，默认同级 DevLib/build/repo，只允许解析日志模块。
- 相机快照回调、业务埋点及操作完成状态继续由 LensDock 提供。CameraLogInfo 是可选兼容接口，普通宿主不传即可。

## 已执行证据

- DevLib：日志库 24 项 JVM 测试，零失败、零跳过；工作台目录 6 项 JVM 测试，零失败、零跳过。
- 原生符号工具 4 项 Python 测试通过；目录布局 3 项及演示目录脚本 11 项测试通过。
- DevLib 日志库本地发布成功：AAR、sources、POM、Gradle metadata 均生成。POM 保留 core-base:1.3.1 与 AndroidX 依赖；没有上传远端。
- DevLib assembleDebug 成功；APK 目录校验通过：22 个库、21 个公共页面，路由 SPI 完整。
- 两仓库 Agent 结构、配置及架构静态检查通过；架构检查保留 DevLib 34 / LensDock 8 项警告（动态依赖等，不能替代 Gradle 解析）。新增演示入口与 Application 源码检查 2 文件、0 发现。
- 对 ALog、WorkspacePolicy、LibraryDetailActivity 的整文件补充扫描发现 10 项 ALog 原有私有方法缺中文注释；均不在本轮修改声明内，不批量重写上游。本轮新增 reportSink 和修改的 log 方法已补中文文档。
- 首次 DevLib 构建因 Java native memory 不足中断；降低并发和 JVM 内存后重试成功，未更改项目全局构建参数。

## 补充检查发现

LensDock 扩展设备测试源码编译先发现宿主未声明 AndroidX Test/JUnit 依赖；已用既有 pressLibs 目录补齐。重试后仅剩 PhotoFlowTest.kt:769 的 CameraPresetPopup 旧构造调用缺少 mode/options/initial/custom，涉及原有预设弹层测试，与日志 API 迁移无关。本轮不修改预设业务/测试；设备测试源码整体编译仍为失败，不能称设备测试通过。日志相关测试源码本次未再报告错误，但不单独宣称其编译验收通过。

## 剩余验证

- DevLib 最后增量构建与工作台测试成功；日志库 Lint 为 0 errors、8 warnings（原有布局适配等提醒），不等同视觉通过。
- LensDock 最终 `:app:assembleDebug :feature:feature_app:testDebugUnitTest :app:dependencyInsight --dependency core-log-report --configuration debugRuntimeClasspath` 成功。475 项 JVM 测试零失败、零跳过，解析确认消费 com.wkq.dev:core-log-report:0.1.0-SNAPSHOT。
- LensDock 合并 Manifest 包含两个日志页面及 com.wkq.lensdock.logreport Provider；重复类检查通过。原源码目录已移除，业务埋点保持在宿主。
- 迁移、发布产物及两宿主构建条件通过；扩展设备测试源码整体编译未通过，原因见上节。真机与视觉条件未验证。

本次按迁移范围保留原页面与交互，新增入口复用工作台现有容器；不重新进行页面设计。未执行 adb、真机、邮件客户端、SAF、崩溃/ANR/native 现场采集、视觉或性能验收。构建及静态结果不证明这些行为通过。

## 接入限制

当前仅发布到 DevLib/build/repo；换电脑或清理该目录后需重做本地发布，或通过 devlibRepository 指向完整仓库。没有提交、推送、远端发布或自动 CI。详见 LensDock/doc/日志库迁移DevLib.md 与库 README。

## 主要执行命令

DevLib：

```text
./gradlew.bat :core:core_log_report:testDebugUnitTest :core:core_log_report:publishReleasePublicationToDevLibLocalRepository :feature:feature_app:testDebugUnitTest :app:assembleDebug
./gradlew.bat :core:core_log_report:lintDebug :feature:feature_app:testDebugUnitTest :app:assembleDebug
python -B core/functional/core_log_report/scripts/test_native_symbols.py
python -B scripts/test_core_layout.py
python -B scripts/test_verify_demo_catalog.py
python -B scripts/verify_demo_catalog.py --apk app/build/outputs/apk/debug/app-debug.apk
```

LensDock：

```text
./gradlew.bat :app:assembleDebug :feature:feature_app:testDebugUnitTest :app:dependencyInsight --dependency core-log-report --configuration debugRuntimeClasspath
```

构建统一临时加 `--no-parallel --max-workers=2 -Dorg.gradle.jvmargs="-Xmx1536m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8"`；未写入用户已有 gradle.properties。

日志证据在 doc/testing/evidence/2026-11-23-log-report-migration/；LensDock 保存同名迁移报告与自身构建证据。源码整文件历史注释缺口与架构警告如上，未用静态检查代替运行验收。
