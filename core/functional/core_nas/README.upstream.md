# 可复用 Android NAS 库

`com.wkq.naslib` 提供 SMB2/3、WebDAV、SFTP 的独立访问会话、文件枚举/读取/新建写入/移动/删除、SMB 共享枚举、DNS-SD 发现及 HTTPS 证书检查。复用已有协议实现，没有新增协议栈。

库没有任何项目模块依赖，不包含 UI、Room、WorkManager、账号或 Pro 门禁；账号持久化、权限弹窗、危险操作确认、同步计划、任务恢复和流量策略由宿主负责。现有应用通过 `core_transfer` 的薄适配层接入，原有模型和调用入口保留。

## 在其他项目接入

本工程执行：

```powershell
.\gradlew.bat :core:lib_nas:publishReleasePublicationToReusableRepository
```

生成 `core/lib_nas/build/repo/` Maven 目录，其中包含 `com.wkq:nas-client:0.1.0` 的 AAR、源码、POM 和 Gradle module metadata。版本维护在根 `gradle/libs.versions.toml` 的 `nas-library`。将完整 repo 目录复制到其他项目或内部 Maven 仓库，在 settings 的 dependencyResolutionManagement.repositories 配置该 Maven 路径，然后添加：

```groovy
implementation 'com.wkq:nas-client:0.1.0'
```

协议依赖仍需通过宿主已有的 Maven Central 等仓库解析。**仅复制单个 AAR 不会自动带上 jcifs-ng/sshj/OkHttp/协程依赖**，优先使用带 POM 的 Maven 目录。当前发布目标只是本机 build/repo，不会上传外部仓库。若直接复制源码模块，需要同步根版本目录中本模块使用的插件和依赖别名。

## 使用

所有会话方法都是阻塞 IO，在 `Dispatchers.IO` 或自己的工作线程串行使用；不共享一个会话做并发调用，关闭读取流和会话：

示例使用协程，宿主需显式声明自己的协程依赖；也可使用已有后台执行器。

```kotlin
withContext(Dispatchers.IO) {
    val connection = NasConnection(
        protocol = NasProtocol.WEBDAV,
        root = "https://nas.example.test/documents/",
        username = username,
        password = password
    )
    NasClient.open(connection).use { nas ->
        val entries = nas.list("")
        nas.read("report.pdf").use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
    }
}
```

- `NasClient.test(connection)` 检查根目录；`NasClient.listSmbShares` 只接受 `smb://host/` 服务器根。
- `NasDiscovery(context).discover { partial -> ... }` 为可取消 suspend 方法，只发现广播 DNS-SD 的服务，不扫描整个网段，不自动信任主机。
- SFTP 必须提供可信 `hostKeySha256`。HTTPS 默认使用系统信任；自签证书由 `WebDavCertificates.inspect` 获取候选指纹，宿主展示并由用户确认后再保存为连接的 `hostKeySha256`。检查方法不发送 HTTP 认证，不能把观察到的指纹自动当成可信。
- `NasConnection.toString()` 不包含地址、用户名或口令；库不保存凭据。URI 不允许 userInfo、查询串和 fragment，不将密码拼进 URL。
- `NasException.error/retryable` 是公开错误契约。输入流读取期间的协议异常也按此分类；宿主 `visit/progress/check` 回调的异常原样传播，保留取消/暂停语义。

## 权限与安全边界

Manifest 提供 INTERNET、ACCESS_NETWORK_STATE、ACCESS_LOCAL_NETWORK 声明。宿主 targetSdk >= 37 时，在 Android 17+ 访问局域网前申请局域网权限；库不弹窗、不持有 Activity。HTTPS 以外的 HTTP 是否允许由宿主最小范围 network security config 决定，库不全局放开明文。

所有访问路径是根内相对路径；拒绝绝对路径、反斜杠、NUL、`.`/`..`。**保留服务器已有文件名的原始 Unicode，不将 NFD 自动改成 NFC。** 同形名称不能作为同一文件处理。

`capabilities` 区分各协议实现支持的行为，不保证任意 NAS 固件支持所有操作。写入与 moveNew 拒绝覆盖已有目标；删除需要宿主先预览确认。不会承诺所有协议原子重命名、字节续传或服务端复制。流式写入不代表业务传输已校验完成，完整性与恢复需宿主任务引擎处理。

## 构建与验证

```powershell
.\gradlew.bat :core:lib_nas:testDebugUnitTest :core:lib_nas:bundleReleaseAar
.\gradlew.bat :core:lib_nas:dependencies --configuration releaseRuntimeClasspath
```

当前基线：minSdk 24、compileSdk 37、JVM 17。默认依赖版本与安全兼容配置沿用本工程版本目录；第三方许可/NOTICE 随正式发行核验，迁移项目时不能省略。JVM 的 MockWebServer 测试不代表真实 SMB/WebDAV/SFTP NAS 的设备兼容性已验收。
