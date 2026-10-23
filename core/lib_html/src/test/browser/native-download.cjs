// 电脑端回归；配合 BrowserFixture。入口含凭据，仅从本机文件读取，不写报告。
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { webkit, chromium } = require(process.env.DROPLAN_PLAYWRIGHT_MODULE || 'playwright');
const root = process.cwd();
const entry = fs.readFileSync(process.env.DROPLAN_ENTRY_FILE || '.ai-work/browser-entry.txt', 'utf8').trim();
const base = new URL(entry).origin;
const token = new URL(entry).hash.slice(1);
const output = path.resolve(process.env.DROPLAN_EVIDENCE_DIR || '.ai-work/safari-browser');
fs.mkdirSync(output, { recursive: true });
const engine = process.env.DROPLAN_BROWSER || 'webkit';
async function api(p, cookie, method = 'GET', body, extra = {}) {
  const response = await fetch(base + '/api/v1' + p, { method, headers: {
    'X-DropLAN': '1', ...(cookie ? { Cookie: cookie } : {}),
    ...(body ? {'Content-Type': 'application/json'} : {}), ...extra
  }, body: body ? JSON.stringify(body) : undefined });
  assert.ok(response.ok, p + ': ' + response.status);
  return { data: await response.json(), headers: response.headers };
}
async function blocker() {
  const paired = (await api('/pairings', null, 'POST', { token })).data;
  const approved = await api('/pairings/' + paired.id, null, 'GET', null, { 'X-Pairing-Ticket': paired.ticket });
  const cookie = approved.headers.get('set-cookie').split(';')[0];
  const file = (await api('/manifest', cookie)).data.items[0];
  const task = (await api('/downloads', cookie, 'POST', {fileId:file.id}, {'Idempotency-Key':crypto.randomUUID()})).data;
  return {cookie,task};
}
(async () => {
  let browser, context;
  const blockers=[];
  try {
    browser = await ({webkit,chromium}[engine]).launch({headless:true,
      ...(process.env.DROPLAN_BROWSER_EXECUTABLE ? {executablePath:process.env.DROPLAN_BROWSER_EXECUTABLE} : {})});
    context = await browser.newContext({acceptDownloads:true,viewport:{width:320,height:740},locale:'zh-CN'});
    const page = await context.newPage();
    // 禁用可选文件选择器，验证发送端选择WS时也能使用原生下载。
    await page.addInitScript(() => { window.showSaveFilePicker = undefined; });
    const errors=[];page.on('pageerror',e=>errors.push(e.message));
    let requests=0, downloads=0;
    page.on('download',()=>downloads++);
    page.on('request',request=>{if(new URL(request.url()).pathname.startsWith('/api/v1/files/'))requests++;});
    await page.goto(entry);
    const security=await context.request.get(base);
    assert.ok(security.headers()['content-security-policy'].includes("frame-ancestors 'none'"));
    assert.equal(security.headers()['x-frame-options'],'DENY');
    await page.locator('#pair-form button').click();
    await page.locator('#receiver').waitFor({state:'visible'});
    assert.equal(await page.locator('iframe').count(),0);
    assert.equal(await page.locator('#task-panel').count(),0,'Tasks are displayed inside file cards');
    assert.equal(await page.locator('#help').getAttribute('open'),null);
    assert.equal(await page.locator('#advanced-help').isVisible(),false);
    await page.screenshot({path:path.join(output,'receiver-simple-320.png'),fullPage:true});
    await page.emulateMedia({colorScheme:'dark'});
    await page.screenshot({path:path.join(output,'receiver-simple-dark-320.png'),fullPage:true});
    await page.emulateMedia({colorScheme:'light'});
    const b1=await blocker();blockers.push(b1);const b2=await blocker();blockers.push(b2);
    await page.locator('#files > li > button').first().click();
    await page.waitForFunction(()=>document.querySelector('#files').textContent.includes('排队中'));
    assert.equal(requests,0,'排队期间不应自动下载');
    assert.equal(downloads,0,'排队期间不应触发下载事件');
    await api('/downloads/'+b1.task.id,b1.cookie,'DELETE');
    const ready=page.locator('#files a.download').first();await ready.waitFor();
    assert.ok(await ready.getAttribute('download'));
    assert.notEqual(await ready.getAttribute('target'),'downloadFrame');
    assert.equal(requests,0,'任务就绪后仍应等待用户点击');
    assert.equal(downloads,0,'任务就绪后仍应等待用户点击');
    await page.evaluate(()=>document.addEventListener('click',event=>{
      if(event.target.closest('a.download')) window.testDownloadClickTrusted=event.isTrusted;
    },true));
    const promise=page.waitForEvent('download');await ready.click();const d=await promise;
    assert.equal(await page.evaluate(()=>window.testDownloadClickTrusted),true);
    const downloadPath=path.join(output,'download.bin');await d.saveAs(downloadPath);
    assert.equal(await d.failure(),null);
    assert.equal(await page.locator('#save-help').getAttribute('open'),null);
    assert.ok((await page.locator('#save-help').textContent()).includes('下载项'));
    const bytes=fs.readFileSync(downloadPath);assert.equal(bytes.length,16*1024*1024);
    const sha256=crypto.createHash('sha256').update(bytes).digest('hex');
    assert.equal(sha256,'287507f403176f1f5b22b9a4d9cb49f7d7f88ac19e406b5ae87ce109564846bd');
    await page.evaluate(() => { window.stableRow = document.querySelector('#files > li'); });
    await page.waitForTimeout(2300);
    assert.equal(await page.evaluate(() => window.stableRow === document.querySelector('#files > li')),true);
    await page.emulateMedia({reducedMotion:'reduce'});
    assert.equal(await page.locator('#files > li').first().evaluate(node => getComputedStyle(node).animationName),'none');
    await page.emulateMedia({reducedMotion:'no-preference'});
    const beforeReload=requests;
    await page.reload();await page.waitForFunction(()=>document.querySelector('#files').textContent.includes('发送完成'));
    await page.locator('#help > summary').click();
    await page.locator('#save-help > summary').click();
    assert.equal(await page.locator('#save-help').getAttribute('open'),'');
    assert.equal(requests,beforeReload,'刷新不应发起新文件请求');
    assert.equal(downloads,1,'刷新不应重启下载');
    assert.equal(await page.locator('#files img').count(),0);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth),320);
    await page.screenshot({path:path.join(output,'receiver-320.png'),fullPage:true});
    await page.locator('#language').selectOption('en');
    await page.waitForFunction(()=>document.querySelector('#files').textContent.includes('Sent'));
    assert.ok((await page.locator('#save-help').textContent()).includes('Show in folder'));
    // 无 SSE 的回退路径：服务端不再返回的历史任务不能永久留在页面上。
    await page.addInitScript(()=>{window.EventSource=undefined;});
    await page.reload();
    await page.waitForFunction(()=>document.querySelector('#files .transfer-status').textContent.length>0);
    await page.route('**/api/v1/downloads',route=>route.request().method()==='GET'
      ? route.fulfill({status:200,contentType:'application/json',body:'{"tasks":[]}'}) : route.continue());
    await page.waitForFunction(()=>document.querySelector('#files .transfer-status').textContent.length===0);
    assert.deepEqual(errors,[]);
    const result={engine,desktopOnly:true,queueRequiresClick:true,downloadAttribute:true,hiddenFrames:0,
      refreshRestoresSent:true,automaticDownloads:0,nativeDownloadEvents:downloads,observedFileRequests:requests,bytes:bytes.length,sha256,
      xssImages:0,viewport:320,pageErrors:errors,prunesRemovedHistory:true,trustedDownloadClick:true,saveLocationHelp:true};
    fs.writeFileSync(path.join(output,'results.json'),JSON.stringify(result,null,2));
    fs.unlinkSync(downloadPath);console.log(JSON.stringify(result));
  } finally {
    for(const b of blockers)await api('/downloads/'+b.task.id,b.cookie,'DELETE').catch(()=>{});
    await context?.close();await browser?.close();
  }
})().catch(e=>{console.error(e.stack);process.exitCode=1;});
