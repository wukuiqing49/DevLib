# 原生浏览器下载回归（仅电脑）

脚本 `native-download.cjs` 连接 `BrowserFixture` 的回环服务，不连接真机。依赖开发机安装的 Playwright 与对应浏览器，未加入应用运行期依赖。

先编译 `core/lib_drop` 的 Debug 单元测试源码，并用其测试 classpath 运行 `com.wkq.droplan.drop.BrowserFixture <项目根目录> WEBSOCKET`。夹具自动批准配对，只有合成数据；仅供本机测试。

环境变量：

- `DROPLAN_PLAYWRIGHT_MODULE`：Playwright 模块路径；默认按 Node 模块解析查找 `playwright`。
- `PLAYWRIGHT_BROWSERS_PATH`：Playwright 浏览器安装目录（可选）。
- `DROPLAN_BROWSER`：`webkit`（默认）或 `chromium`。
- `DROPLAN_BROWSER_EXECUTABLE`：指定浏览器可执行文件（可选）。
- `DROPLAN_ENTRY_FILE`：夹具生成的私有入口文件，默认 `.ai-work/browser-entry.txt`，禁止提交其内容。
- `DROPLAN_EVIDENCE_DIR`：测试结果目录，默认 `.ai-work/safari-browser`。

运行：`node core/lib_html/src/test/browser/native-download.cjs`。

断言实际两会话占满名额后第三会话排队、就绪后等待真实点击、16MiB 原生下载哈希、刷新无重复下载、WS 不可用时回退、320px 无横向溢出、安全响应头保留及历史任务清理。最后一项通过拦截任务列表模拟服务端淘汰记录，其他下载走真实回环服务。

测试结束在 `.ai-work/browser-fixture.stop` 创建文件以关闭夹具。浏览器由脚本 finally 关闭。桌面 WebKit 通过不代表 iPhone/iPad Safari 已验收。

多语言回归：同一夹具运行 `node core/lib_html/src/test/browser/languages.cjs`，复用上述环境变量。验证日语浏览器自动匹配、八种语言切换、下载位置说明和320px横向溢出；可分别用 Chromium/WebKit 运行。

连接推荐回归：`node core/lib_html/src/test/browser/connection-advice.cjs` 使用 Chromium，夹具模式为 WEBSOCKET。先真实原生 HTTP 下载，再通过测试文件选择器适配器写入 OPFS，验证16MiB两条路径哈希一致、真实鉴权推荐响应、无自动下载、网络变化清除推荐、320px无溢出。OPFS适配器不代替原生文件选择器验收，也不能用于判断真实无线速度。

## 简洁接收页回归

文件与任务合并为同一行；保存帮助、测速及高级传输收纳到 `#help`，默认关闭，点击下载不自动展开。native-download 额外验证无独立任务面板、文件节点刷新复用、减少动态效果设置，以及 320px 日夜首屏截图。languages 通过帮助入口检查八种语言；connection-advice 通过帮助内高级入口验证原生 HTTP 与 WS 两条真实流式下载路径。截图中的特殊字符文件名为 XSS 合成夹具，不是用户文件。
