# lib_auth_credentials

来源 DropLAN 的独立 Credential Manager 认证适配，公开包名保持 `com.wkq.google.auth`。宿主配置 OAuth 项目及 Web Client ID，并负责账号业务和凭据。

```groovy
implementation project(':core:lib_auth_credentials')
```

需要 Firebase 认证时另外依赖 `lib_auth_firebase`，由宿主初始化 FirebaseApp。此模块可单独使用，不引入支付、推送或原产品收费规则。

本地发布坐标 `com.wkq.dev:lib-auth-credentials:0.1.0-SNAPSHOT`。验证状态见 `doc/testing/TEST_REPORT.md`。
