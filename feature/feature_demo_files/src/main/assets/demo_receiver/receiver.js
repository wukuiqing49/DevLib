'use strict';
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
  if (!response.ok) throw new Error('请求失败（' + response.status + '）');
  return response.json();
}
function showError(error) { statusText.textContent = error.message; }
async function listFiles() {
  files.replaceChildren();
  let cursor = 0;
  do {
    const page = await api('/manifest?cursor=' + cursor);
    for (const file of page.items) {
      const row = document.createElement('li');
      const button = document.createElement('button');
      button.textContent = '准备下载';
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
            statusText.textContent = '正在排队，请保持页面打开';
            await wait();
            task = await api('/downloads/' + encodeURIComponent(task.id));
          }
          if (task.state !== 'READY') throw new Error('下载任务未就绪，请重新申请');
          const link = document.createElement('a');
          link.href = '/api/v1/files/' + encodeURIComponent(file.id) + '?task=' + encodeURIComponent(task.id);
          link.target = 'download';
          link.textContent = '点击下载';
          row.append(link);
          statusText.textContent = '已准备下载链接，请点击；文件是否保存成功以浏览器为准';
        } catch (error) { button.disabled = false; showError(error); }
      };
    }
    cursor = page.nextCursor;
  } while (cursor !== null);
  statusText.textContent = '文件列表已更新';
}
pairButton.onclick = async () => {
  pairButton.disabled = true;
  try {
    if (!token) throw new Error('请重新打开手机显示的完整分享地址');
    const pairing = await api('/pairings', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token })
    });
    let state = 'PENDING';
    while (state === 'PENDING') {
      statusText.textContent = '等待手机批准连接';
      await wait();
      state = (await api('/pairings/' + encodeURIComponent(pairing.id), {
        headers: { 'X-Pairing-Ticket': pairing.ticket }
      })).state;
    }
    if (state !== 'APPROVED') throw new Error('连接未获批准，请重新申请');
    refreshButton.disabled = false;
    await listFiles();
  } catch (error) { pairButton.disabled = false; showError(error); }
};
refreshButton.onclick = () => listFiles().catch(showError);
