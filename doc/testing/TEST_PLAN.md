# DevLib 首次迁入验证计划

## 范围与环境

日期：2026-10-23，Asia/Singapore。首次接入，无历史通过基线。源码为未提交工作区；最终报告记录 SHA-256 工作区指纹。

工具链：JDK 17、Gradle 8.13、AGP 8.13.2、Kotlin 2.1.21、KSP 2.1.21-2.0.1、Android SDK 36。演示包名 com.wkq.dev，debug，versionName 0.1.0。Native 使用各中央快照声明的 NDK/CMake。

## 用例与影响关系

| ID | 范围与源码 | 选择原因 | 命令与预期 | 方法 |
| --- | --- | --- | --- | --- |
| STRUCTURE | AGENTS.md、.agents/config | 库定位变为真实多模块工程 | Python Agent/架构/构建校验通过；core 不依赖 app/feature | 静态 |
| BUILD-CORE | core/*、gradle/*、settings.gradle | 全量新迁入，统一工具链和依赖 | assembleLibraries；每个 Android 库产出 AAR、JVM 库产出 JAR | 构建 |
| BUILD-APP | app、feature、基础库 | 演示宿主直接使用本地源码，避免旧 AAR 重复类 | :app:assembleDebug；包名 com.wkq.dev | 构建 |
| TEST-FOUNDATION | core_base/utils/network/router/xpopup | 基础库会影响所有消费者，路由含 KSP/Gradle 插件 | testLibraries 中相应 JVM 测试通过 | JVM |
| TEST-COMMERCIAL | auth、push、billing、entitlement | 分包与宿主策略边界变化 | 既有购买恢复、缓存、推送与新增权益策略测试通过 | JVM/Robolectric |
| TEST-FILES | drop/html/upload/transfer/archive | 模块迁移、通知路由、资源与付费解耦 | 协议、路径、冲突、Room 恢复与宿主通知路由测试通过 | JVM/Robolectric |
| TEST-IMAGES | metadata/operations/image/RAW/QR/media/id-photo | DTO 解耦、Native 相对路径和共享操作契约变化 | 现有元数据、预算、渲染、规格与图片处理单元测试通过 | JVM/Robolectric |
| TEST-DOCUMENT | lib_document、core_pdf、lib_report_pdf | 去掉屋顶业务模板策略，保留写入与排版引擎 | 现有 DOCX/XLSX/PDF 测试通过 | JVM/Robolectric |
| TEST-MEDIA | camera/beauty/live/crash/devhub | 新迁入后端和 SDK，统一工具链 | 现有 JVM 测试通过；无现有测试时明确标注 | JVM/Robolectric |
| PUBLISH | gradle/library-publish.gradle、core/* | 独立库必须能被外部消费 | publishLibrariesToLocalRepository；每库生成独立 POM、sources 与产物，路由插件包含 marker | 本地发布 |
| CONSUMER | samples/published-consumer | 源码构建无法证明 POM 和传递依赖正确 | 仅使用发布坐标编译，无 project()；可见 XPopup、Retrofit、路由和功能 API | 独立工程编译 |

## 运行期缺口

本轮侧重迁入、独立构建、既有 JVM 测试和发布消费验证，不把构建成功当功能或发布验收。

支付/OAuth/FCM/Firebase/RTC/第三方美颜授权需要宿主账号与配置；真实 SMB/SFTP/WebDAV/FTP、热点、SAF、后台恢复、跨浏览器大文件、Native ARM 运行、模型下载与证件照数据签名需要独立集成和设备验收。未执行项在报告中明确列出。

原业务项目未修改，未切换到 DevLib 依赖；照片业务数据库测试和 RoofQuote 模板策略测试保留原项目，不作为已迁入通用库的测试。既有七份支付实现只做差异标记，未宣称语义合并。

## 证据

最新结果：TEST_REPORT.md。通过基线：TEST_BASELINE.json。关键摘要与测试 XML 放 evidence/2026-10-23-import/；完整临时构建日志位于 .ai-work/migration/，不纳入版本控制。
