# lib_auth_firebase

来源 DropLAN 的独立认证适配，公开包名保持 `com.wkq.google.auth`。宿主配置 OAuth/Firebase 项目并负责账号业务和凭据。

```groovy
implementation project(':core:lib_auth_firebase')
```

Firebase 模块不初始化默认 FirebaseApp，需宿主按自己的项目配置初始化。它不引入支付、推送或原产品收费规则。

本地发布坐标 `com.wkq.dev:lib-auth-firebase:0.1.0-SNAPSHOT`。验证状态见 `doc/testing/TEST_REPORT.md`。
