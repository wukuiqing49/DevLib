# Published consumer

该工程不包含 project() 依赖，用于验证 DevLib 发布的 AAR/JAR、POM 和传递依赖。

路由通过发布的 `com.wkq.router` 插件 marker 接入，自动添加 API 与 KSP processor；源码引用生成的 RouterPaths，缺少代码生成时编译必须失败。

先在 DevLib 根目录运行 `gradlew.bat publishLibrariesToLocalRepository`，再运行 `gradlew.bat -p samples/published-consumer compileDebugKotlin`。SDK 通过本机 ANDROID_HOME 或该工程被忽略的 local.properties 提供。

现已覆盖全部 10 个功能库的公开入口，以及 Base/Utils/Network、XPopup 与 Router 插件传递依赖；验证公开签名能否独立编译，不代表所有 API 或运行期功能验收。
