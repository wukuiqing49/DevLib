# core_network 发布指南（DevLib）

本文件描述 DevLib 的实际配置，替代迁入时 AndroidCoreNetwork 的旧发布说明。源码和公开包名保持不变，原仓库保持原样。构建扩展位于 `gradle/network-publish.gradle`，基础本地发布复用 `gradle/library-publish.gradle`。

## 产物与版本

标准坐标：`com.wkq.dev:core-network:<版本>`；默认版本来自根 `devlibVersion`，当前为 `0.1.0-SNAPSHOT`。通过 `-PnetworkPublishVersion=0.1.0` 或 `NETWORK_PUBLISH_VERSION` 覆盖，仅影响网络库发布。产物包含 release AAR、sources JAR、POM 和 Gradle module metadata，保留传递依赖。

所有命令从项目根执行；需要现有 Android SDK 37、JDK 17 与 Gradle Wrapper。不要同时引用源码模块和同一网络库的 Maven AAR。

## 本地 Maven

```powershell
.\gradlew.bat :core:core_network:publishReleasePublicationToDevLibLocalRepository
.\gradlew.bat :core:core_network:publishReleasePublicationToMavenLocal
```

前者输出根 `build/repo`，后者输出用户 Maven Local。消费端按所选仓库声明 `maven { url = uri("本地仓库目录") }` 或 `mavenLocal()`，再声明标准坐标。

## 通用 Maven（私有 Nexus / Artifactory 等）

在用户级 `~/.gradle/gradle.properties` 配置下列键，或者使用对应环境变量；不要把真实凭据写入项目文件或命令行：

| Gradle 属性 | 环境变量 | 用途 |
| --- | --- | --- |
| networkPublishVersion | NETWORK_PUBLISH_VERSION | 网络库发布版本 |
| networkMavenUrl | NETWORK_MAVEN_URL | HTTPS 发布仓库地址，或本地 file URI |
| networkMavenUser | NETWORK_MAVEN_USER | 可选认证用户名 |
| networkMavenPassword | NETWORK_MAVEN_PASSWORD | 可选认证密码/token |

```powershell
.\gradlew.bat :core:core_network:publishReleasePublicationToNetworkMavenRepository
```

只有配置 URL 后才注册此目标。用户名和密码同时配置；无认证仓库可同时省略。仓库 URL 禁止携带凭据或查询参数。Maven Central 的签名、命名空间认证与 Portal 发布流程不在本配置范围内。

## GitHub Packages

| Gradle 属性 | 环境变量 | 用途 |
| --- | --- | --- |
| networkGitHubRepository | NETWORK_GITHUB_REPOSITORY | 默认 wukuiqing49/DevLib，允许配置其他 owner/repository |
| gpr.user | GITHUB_ACTOR | GitHub 用户名 |
| gpr.key | GITHUB_TOKEN | 具有目标仓库包写入权限的凭据 |

```powershell
.\gradlew.bat :core:core_network:publishReleasePublicationToGitHubPackagesRepository '-PnetworkPublishVersion=0.1.0'
```

目标为 `https://maven.pkg.github.com/<owner>/<repository>`，标准坐标不变。消费端需添加该 Maven 仓库，并按包可见性配置读权限。普通编译、本地发布不要求 GitHub 凭据，实际上传前才检查。具体 token 类型、权限及组织授权以 GitHub 当前要求为准。

## JitPack

根 `jitpack.yml` 使用 JDK 17，仅把网络库 release 安装到 Maven Local，版本取 JitPack 的 `VERSION`。JitPack 在拉取指定提交/tag 后自行执行构建，不需要新增 GitHub Actions。

JitPack 使用它自己的 `com.github...` 服务坐标，不等同于上述标准 Maven 坐标。推送配置并在 JitPack 构建成功后，以该版本页面实际列出的 artifact 坐标接入，不能把尚未验证的坐标当成已上线产物。远端必须能提供 SDK 37 和当前工具链；本地成功不代表 JitPack 镜像已满足这些条件。

## 边界与验证

不要运行笼统的 `publish` 任务来试配置，它可能上传到全部已配置仓库；明确选择上述单个目标。配置不会自动提交、打 tag 或 push，也未新增/修改 GitHub Actions。远端发布由维护者准备凭据和版本后手动执行。

本地验证应覆盖网络库单测、release AAR、本地文件 Maven 发布、版本覆盖及 POM/源码附件；真实 GitHub Packages、私有 Maven 和 JitPack 构建需另行验收。

参考：[Gradle Maven Publish](https://docs.gradle.org/current/userguide/publishing_maven.html)、[JitPack 构建配置](https://docs.jitpack.io/building/)。
