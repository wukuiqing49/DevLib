# 内置网页、沉浸式与 JS 契约

## 容器边界

通知在 App 内打开指定 HTTPS URL，不替换成外部浏览器。复用独立远程容器，保留原本仅加载 assets 的帮助页安全策略；拒绝 URL 凭据、控制字符、file/content/javascript/intent、本地地址；禁文件/内容访问、混合内容和不必要的第三方 Cookie，SSL 错误取消。

“允许浏览”不等于“允许原生交互”。桥用精确 HTTPS origin 白名单、主框架、当前来源和文档代次共同验证；推送 URL 不自动授信，不能用域名通配或只检查首次导航。优先 WebViewCompat.addWebMessageListener；旧 provider 不支持时可浏览并提示升级，不注入向所有 frame 暴露的备用桥。

显示标题、来源、关闭、进度、错误与重试；重试当前失败 URL，加载超时恢复入口。沉浸式实际 hide systemBars，保留原生退出按钮，返回先退出全屏，页面跳转/错误恢复工具栏。Insets 兼顾刘海、系统手势、IME 和横屏，不写死高度。onDestroy 移除桥、取消等待、停止并销毁 WebView，旧页异步结果不得改写新页 UI/账号。

参考 [原生桥风险](https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges) 与目标版本 WebKit 文档。

## CaptionMeta 已实现的示例协议

以下为 **CaptionMeta 宿主协议，不是 DevHub SDK API**。其他 App 先明确桥名、能力与原生实现，不能复制名称就宣称支持登录/会员/兑换。实际示例源码：宿主 CampaignBridgeRequest、CampaignNativeActions、CampaignWeb Activity；DevHub CMS 的 `cms/public/captionmeta-bridge.js` 与 `captionmeta-bridge-test.html`。

请求 `window.CaptionMeta.postMessage(JSON.stringify({id,action,params}))`，响应由 CaptionMeta.onmessage 的 event.data 解析 JSON。请求 ID 为1～64位字母数字下划线连字符，总长≤8192；同文档最多256个唯一 ID，重复/超量拒绝，不能再执行动作。

```json
{"id":"request_1","action":"setImmersive","params":{"enabled":true}}
```

```json
{"id":"request_1","action":"setImmersive","success":true,"error":null,"immersive":true}
```

| action | 参数与成功语义 |
| --- | --- |
| getCapabilities | 返回 protocolVersion=1、actions 和 immersive，先探测再调用 |
| setImmersive | enabled 必须为布尔值，结果含实际 immersive |
| close | 关闭页面，不能保证已销毁文档继续展示回调 |
| openMembership | 仅导航成功，不表示付款成功 |
| login | 原生 Google 登录与关联，不向网页回传 ID Token 或邮箱 |
| openFeedback | 可选 content 字符串≤4096，原生输入和确认后提交 |
| redeem | 可选 code 字符串≤64，原生确认后按服务端 redeemed 判断；不等同 Play 兑换或自动解锁 |

失败返回 success=false 和 error：invalid_request、action_busy、canceled、duplicate_or_excessive_request 等。能识别的非法请求保留合法 id/action，使 H5 结束等待。原生回调还校验文档代次/当前来源；导航销毁旧动作处理器和未提交弹窗，迟到登录结果不得绑定新页账号。

Promise 客户端以 id/action/success 类型关联响应，默认45秒、最多16个 pending、最长120秒；pagehide reject 并清理等待，页面恢复按实现重新允许调用。超时不自动重试写入。使用已存在客户端，不重复覆盖 onmessage：

```html
<button id="fullscreen">进入全屏</button>
<p id="result" role="status"></p>
<script src="/captionmeta-bridge.js"></script>
<script src="/page-actions.js"></script>
```

page-actions.js 完整最小调用示例：

```javascript
document.querySelector('#fullscreen').addEventListener('click', async () => {
  const output = document.querySelector('#result');
  if (!window.CaptionMetaClient?.isAvailable()) {
    output.textContent = '请在支持此交互的 App 内打开。';
    return;
  }
  try {
    const capabilities = await CaptionMetaClient.request('getCapabilities');
    if (!capabilities.actions.includes('setImmersive')) throw new Error('unsupported_action');
    const result = await CaptionMetaClient.request('setImmersive', {enabled: true});
    output.textContent = result.immersive ? '已进入全屏' : '已退出全屏';
  } catch (error) {
    output.textContent = error.code || error.message;
  }
});
```

只绑定用户显式点击，不自动登录/反馈/兑换。使用 textContent 不渲染响应 HTML；无桥时提示或提供普通网页行为。

## CMS 页面和测试

新版 `/p/{id}` 活动页保存草稿和公开版本分开，发布才更新公开页面；页面清洗/CSP 禁脚本，不能把任意 JS 塞到活动编辑器。业务 JS 应放在自有可信 H5，按钮跳转过去；部署域须加入宿主精确白名单。CMS 测试页也需随 Pages 发布，本地文件存在不能证明远程已发布。

分别验证普通浏览器无桥降级、可信主框架能力响应、iframe 不能调用、第三方无桥、非法参数、错 action、重复请求、超时、离页清理、销毁后的迟到结果，以及退出全屏/返回/错误恢复。内存拦截 HTML 只证明本地桥；远程验收还需正文实际加载和响应。真实反馈/兑换/付款只按单独授权，用测试范围操作。
