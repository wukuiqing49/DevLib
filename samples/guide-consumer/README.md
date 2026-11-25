# 独立引导库消费验证

默认依赖本地坐标 `com.wkq.dev:core-guide:0.1.0`；远程模式依赖 `com.github.wukuiqing49.DevLib:core-guide:guide-v0.1.0`。不引用 DevLib 工程模块、Base、XPopup 或工作台资源。

先在根目录发布，再编译消费者：

```powershell
.\gradlew.bat :core:core_guide:publishReleasePublicationToDevLibLocalRepository
.\gradlew.bat --offline -p samples/guide-consumer compileDebugKotlin verifyGuideArtifact
```

SDK 通过本机 ANDROID_HOME 或不提交的 local.properties 配置。此工程是 AAR 接入编译验证，不是已运行的 App。真实宿主 App 仍需配置 targetSdk 37 并负责窗口系统栏策略。

远程验证（不注册本地 Maven 或 mavenLocal，不使用 dependency substitution）：

```powershell
.\gradlew.bat --no-daemon -p samples/guide-consumer compileDebugKotlin verifyGuideArtifact -PguideRemote --refresh-dependencies
```

`-PguideVersion=其他标签` 可验证其他远程版本；默认 `guide-v0.1.0`。校验输出解析坐标、AAR SHA-256，并拒绝意外的 DevLib 宿主传递依赖。
