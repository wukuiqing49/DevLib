'use strict';
// 接收端按浏览器语言选择文案，协议标识、票据和文件名保持原样。
const chinese = /^zh/i.test(navigator.language || 'en');
const text = chinese ? {
  "title": "DevLib 文件接收",
  "heading": "接收共享文件",
  "intro": "请在同一局域网打开手机显示的完整地址，再在手机上批准连接。",
  "security": "普通 HTTP 不加密，请仅在可信网络中使用。",
  "pair": "申请连接",
  "refresh": "刷新文件列表",
  "waiting": "等待申请连接",
  "frame": "文件下载",
  "prepare": "准备下载",
  "queue": "正在排队，请保持页面打开",
  "notReady": "下载任务未就绪，请重新申请",
  "download": "点击下载",
  "ready": "已准备下载链接，请点击；文件是否保存成功以浏览器为准",
  "updated": "文件列表已更新",
  "missing": "请重新打开手机显示的完整分享地址",
  "pending": "等待手机批准连接，请核对请求编号：",
  "denied": "连接未获批准，请重新申请",
  "requestFailed": "请求失败",
  "networkFailed": "连接中断，请检查网络后重试"
} : {
  "title": "DevLib file receiver",
  "heading": "Receive shared files",
  "intro": "Open the complete address shown on the phone from the same local network, then approve the connection on the phone.",
  "security": "HTTP is unencrypted. Use this page only on a trusted network.",
  "pair": "Request connection",
  "refresh": "Refresh file list",
  "waiting": "Ready to request a connection",
  "frame": "File download",
  "prepare": "Prepare download",
  "queue": "Queued. Keep this page open.",
  "notReady": "The download is not ready. Request it again.",
  "download": "Download file",
  "ready": "The download link is ready. Select it to download; check your browser for the save result.",
  "updated": "File list updated",
  "missing": "Reopen the complete sharing address shown on the phone.",
  "pending": "Waiting for phone approval. Verify request ID: ",
  "denied": "Connection was not approved. Request it again.",
  "requestFailed": "Request failed",
  "networkFailed": "Connection interrupted. Check your network and retry."
};
document.documentElement.lang = chinese ? 'zh-CN' : 'en';
document.title = text.title;
for (const id of ['heading', 'intro', 'security', 'pair', 'refresh']) {
  document.getElementById(id).textContent = text[id];
}
document.getElementById('status').textContent = text.waiting;
document.getElementById('download-frame').title = text.frame;

// 凭据仅在当前页面内存中保存，不写存储、日志或请求 URL。
const token = location.hash.slice(1);
history.replaceState(null, '', location.pathname);
const statusText = document.getElementById('status');
const pairButton = document.getElementById('pair');
const refreshButton = document.getElementById('refresh');
const files = document.getElementById('files');
const wait = () => new Promise(resolve => setTimeout(resolve, 1000));
async function api(path, options = {}) {
  const response = await fetch('/api/v1' + path, {
    ...options, credentials: 'same-origin', cache: 'no-store',
    headers: { 'X-DropLAN': '1', ...options.headers }
  });
  if (!response.ok) throw new Error(text.requestFailed + ' (' + response.status + ')');
  return response.json();
}
/** 展示本地化业务错误，浏览器网络异常使用统一提示。 */
function showError(error) { statusText.textContent = error instanceof TypeError ? text.networkFailed : error.message; }
async function listFiles() {
  files.replaceChildren();
  let cursor = 0;
  do {
    const page = await api('/manifest?cursor=' + cursor);
    for (const file of page.items) {
      const row = document.createElement('li');
      const button = document.createElement('button');
      button.textContent = text.prepare;
      row.append(document.createTextNode(file.name + ' '), button);
      files.append(row);
      button.onclick = async () => {
        button.disabled = true;
        try {
          const bytes = crypto.getRandomValues(new Uint8Array(16));
          const key = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
          let task = await api('/downloads', {
            method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key },
            body: JSON.stringify({ fileId: file.id })
          });
          while (task.state === 'QUEUED') {
            statusText.textContent = text.queue;
            await wait();
            task = await api('/downloads/' + encodeURIComponent(task.id));
          }
          if (task.state !== 'READY') throw new Error(text.notReady);
          const link = document.createElement('a');
          link.href = '/api/v1/files/' + encodeURIComponent(file.id) + '?task=' + encodeURIComponent(task.id);
          link.target = 'download';
          link.textContent = text.download;
          row.append(link);
          statusText.textContent = text.ready;
        } catch (error) { button.disabled = false; showError(error); }
      };
    }
    cursor = page.nextCursor;
  } while (cursor !== null);
  statusText.textContent = text.updated;
}
pairButton.onclick = async () => {
  pairButton.disabled = true;
  try {
    if (!token) throw new Error(text.missing);
    const pairing = await api('/pairings', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token })
    });
    let state = 'PENDING';
    while (state === 'PENDING') {
      statusText.textContent = text.pending + pairing.id;
      await wait();
      state = (await api('/pairings/' + encodeURIComponent(pairing.id), {
        headers: { 'X-Pairing-Ticket': pairing.ticket }
      })).state;
    }
    if (state !== 'APPROVED') throw new Error(text.denied);
    refreshButton.disabled = false;
    await listFiles();
  } catch (error) { pairButton.disabled = false; showError(error); }
};
refreshButton.onclick = () => listFiles().catch(showError);
