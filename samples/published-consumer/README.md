# Published consumer

该工程不包含 project() 依赖，用于验证 DevLib 发布的 AAR/JAR、POM 和传递依赖。

路由通过发布的 `com.wkq.router` 插件 marker 接入，自动添加 API 与 KSP processor；源码引用生成的 RouterPaths，缺少代码生成时编译必须失败。

先在 DevLib 根目录运行 `gradlew.bat publishLibrariesToLocalRepository`，再运行 `gradlew.bat -p samples/published-consumer compileDebugKotlin`。SDK 通过本机 ANDROID_HOME 或该工程被忽略的 local.properties 提供。
