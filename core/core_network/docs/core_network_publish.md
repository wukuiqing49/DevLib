# core_network 网络版本发布手册

这份文档用于把 `core_network` 发布成 GitHub 网络依赖，方便其他 Android 项目直接引用。

## 结论

- 公开给别人用，优先走 JitPack。
- JitPack 只需要 GitHub 仓库和 Git tag，使用方不需要 token。
- GitHub Packages 适合私有库或内部库，但使用方也要配置 GitHub token。
- 本工程内部开发仍然使用 `implementation project(':core_network')`。

## 推荐：自动发版脚本

修改代码后优先使用自动发版脚本。脚本会读取本地和远端已有 tag，默认自动执行 patch +1，并完成版本替换、构建校验、本地 Maven 发布、提交、打 tag 和推送：

```powershell
.\scripts\release-core-network.ps1 -AllowDirty
```

脚本会自动执行：

- 更新 README、发布手册和 app 示例里的 `v1.0.x` 引用。
- 执行 `:core_network:compileDebugKotlin`。
- 执行 `:app:assembleDebug -PUSE_LOCAL_CORE_NETWORK=true`，使用本地 `:core_network` 校验 Demo。
- 执行 `:core_network:publishReleasePublicationToMavenLocal`。
- `git commit -m "release core_network x.y.z"`。
- `git tag vx.y.z`。
- `git push origin main` 和 `git push origin vx.y.z`。

如果希望功能代码和发布提交分开，先手动提交功能代码，再执行：

```powershell
.\scripts\release-core-network.ps1
```

如果要把当前未提交改动一起放进发布提交，使用：

```powershell
.\scripts\release-core-network.ps1 -AllowDirty
```

只本地生成提交和 tag、不推送：

```powershell
.\scripts\release-core-network.ps1 -AllowDirty -SkipPush
```

指定 minor、major 或固定版本：

```powershell
.\scripts\release-core-network.ps1 -Bump minor -AllowDirty
.\scripts\release-core-network.ps1 -Bump major -AllowDirty
.\scripts\release-core-network.ps1 -Version 1.2.3 -AllowDirty
```

脚本不会覆盖已经存在的 tag。推送完成后打开 JitPack 页面触发构建：

```text
https://jitpack.io/#wukuiqing49/AndroidCoreNetwork/vx.y.z
```

## 当前发布配置

已配置文件：

```text
core_network/build.gradle
jitpack.yml
gradlew / gradlew.bat
gradle/wrapper/gradle-wrapper.properties
core_network/docs/core_network_publish.md
```

`core_network` 已支持：

- release AAR 发布。
- sources jar 发布。
- consumer ProGuard 规则随 AAR 传递。
- JitPack 网络引用。
- GitHub Packages 发布。

## 发版前检查

每次发布前先确认：

```bash
./gradlew :core_network:compileDebugKotlin
```

建议再验证一次本地 Maven 发布：

```bash
./gradlew :core_network:publishReleasePublicationToMavenLocal "-PPOM_GROUP_ID=com.github.local" "-PPOM_VERSION=1.0.6"
```

Windows PowerShell：

```powershell
.\gradlew.bat :core_network:compileDebugKotlin
.\gradlew.bat :core_network:publishReleasePublicationToMavenLocal "-PPOM_GROUP_ID=com.github.local" "-PPOM_VERSION=1.0.6"
```

如果这两步失败，不要打 tag 发布。

## 手动发布方式：JitPack

### 1. 提交代码

JitPack 只读取 GitHub 上的代码，本地未提交内容不会发布。

```bash
git status
git add .
git commit -m "release core_network 1.0.6"
git push origin master
```

### 2. 打版本 tag

示例发布 `v1.0.6`：

```bash
git tag v1.0.6
git push origin v1.0.6
```

如果 tag 打错了，不建议覆盖已经给别人用过的版本。还没公开使用时可以删除重打：

```bash
git tag -d v1.0.6
git push origin :refs/tags/v1.0.6
git tag v1.0.6
git push origin v1.0.6
```

### 3. 到 JitPack 构建

打开：

```text
https://jitpack.io/#wukuiqing49/AndroidCoreNetwork
```

输入 tag：

```text
v1.0.6
```

点击 `Get it`，等待构建成功。

### 4. 使用方引用

使用方项目的 `settings.gradle`：

```gradle
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = "https://jitpack.io" }
    }
}
```

使用方业务模块的 `build.gradle`：

```gradle
dependencies {
    implementation "com.github.wukuiqing49:AndroidCoreNetwork:v1.0.6"
}
```

注意：版本号要和 Git tag 完全一致，例如 tag 是 `v1.0.6`，依赖里也写 `v1.0.6`。

## GitHub Packages 发布

这套方式已经配置，但公开库不建议优先使用，因为使用方需要 token。

### 自动发布

已配置 workflow：

```text
.github/workflows/publish-core-network.yml
```

触发方式：

- 推送 `v*` tag，例如 `v1.0.6`。
- 在 GitHub Actions 页面手动运行 workflow，并输入版本号。

### 本地手动发布

需要在本机 `~/.gradle/gradle.properties` 配置：

```properties
gpr.user=GitHub用户名
gpr.key=GitHub Personal Access Token
GITHUB_REPOSITORY=wukuiqing49/AndroidCoreNetwork
```

不要把 token 提交到 Git。

发布命令：

```bash
./gradlew :core_network:publishReleasePublicationToGitHubPackagesRepository "-PPOM_GROUP_ID=com.github.wukuiqing49" "-PPOM_VERSION=1.0.6"
```

Windows PowerShell：

```powershell
.\gradlew.bat :core_network:publishReleasePublicationToGitHubPackagesRepository "-PPOM_GROUP_ID=com.github.wukuiqing49" "-PPOM_VERSION=1.0.6"
```

### 使用方引用 GitHub Packages

使用方需要配置仓库和凭证：

```gradle
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/wukuiqing49/AndroidCoreNetwork")
            credentials {
                username = providers.gradleProperty("gpr.user").get()
                password = providers.gradleProperty("gpr.key").get()
            }
        }
    }
}
```

依赖：

```gradle
dependencies {
    implementation "com.github.wukuiqing49:AndroidCoreNetwork:1.0.6"
}
```

## 基础使用

初始化：

```kotlin
NetManager.init(
    NetConfig.Builder()
        .setBaseUrl("https://api.example.com/")
        .setDebugLogsEnabled(BuildConfig.DEBUG)
        .build()
)
```

普通请求：

```kotlin
val result = Net.request {
    api.getUser()
}
```

没有 `code/message/data` 响应壳的接口：

```kotlin
val result = Net.raw {
    api.getRawJson()
}
```

上传：

```kotlin
Net.upload(file) { part ->
    api.uploadAvatar(part)
}.collect { state ->
    when (state) {
        is UploadState.Progress -> {
            val percent = state.percent
        }
        is UploadState.Success -> {
            val data = state.data
        }
        is UploadState.Error -> {
            val message = state.message
        }
    }
}
```

原始响应上传：

```kotlin
Net.uploadRaw(file) { part ->
    api.uploadRaw(part)
}
```

## 混淆说明

`core_network` 已配置：

```gradle
consumerProguardFiles "consumer-rules.pro"
```

使用方通过 JitPack 或 GitHub Packages 引用时，consumer rules 会随 AAR 传递。正常开启 R8 不需要每个业务项目再单独写一份 `core_network` 专属混淆规则。

如果后续新增以下能力，需要同步检查 `consumer-rules.pro`：

- 反射创建类。
- Gson 解析模型。
- Retrofit 注解服务接口。
- WebView JSBridge。
- Native / so。
- 需要特殊 keep 的三方库。

## 常见问题

### JitPack 构建失败

先本地执行：

```bash
./gradlew :core_network:publishReleasePublicationToMavenLocal "-PPOM_GROUP_ID=com.github.local" "-PPOM_VERSION=1.0.6"
```

本地都失败，先修本地构建。

### 使用方找不到依赖

检查：

- 使用方是否加了 `maven { url = "https://jitpack.io" }`。
- 依赖版本是否和 Git tag 完全一致。
- JitPack 页面对应 tag 是否构建成功。
- 坐标是否写成 `com.github.wukuiqing49:AndroidCoreNetwork:v1.0.6`。

### 使用方编译缺 Retrofit / OkHttp / Coroutines 类

`core_network` 的 public API 暴露了 Retrofit、OkHttp、Coroutines 类型，所以这些依赖在模块内使用 `api`。如果后续 public API 新增了新的外部类型，也要确认对应依赖是否需要 `api`。

### 使用方混淆后崩溃

先确认使用的是最新版本。`core_network` 当前 consumer rules 会传递，正常不需要额外配置。如果新增了反射入口、响应壳或特殊序列化逻辑，要更新 `core_network/consumer-rules.pro` 后重新发版。

## 快速发版命令

发布 `v1.0.6` 的完整命令：

```bash
./gradlew :core_network:compileDebugKotlin
./gradlew :core_network:publishReleasePublicationToMavenLocal "-PPOM_GROUP_ID=com.github.local" "-PPOM_VERSION=1.0.6"
git status
git add .
git commit -m "release core_network 1.0.6"
git push origin master
git tag v1.0.6
git push origin v1.0.6
```

然后打开：

```text
https://jitpack.io/#wukuiqing49/AndroidCoreNetwork
```

点击 `Get it`。
