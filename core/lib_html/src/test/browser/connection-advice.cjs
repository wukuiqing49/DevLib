// 电脑回环服务：真实 HTTP 下载 + WS 分块写 OPFS；文件选择器用测试适配器替代。
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const {chromium} = require(process.env.DROPLAN_PLAYWRIGHT_MODULE || 'playwright');
(async () => {
  const out = path.resolve(process.env.DROPLAN_EVIDENCE_DIR || '.ai-work/connection-advice');
  fs.mkdirSync(out, {recursive:true});
  const browser = await chromium.launch({headless:true,
    ...(process.env.DROPLAN_BROWSER_EXECUTABLE ? {executablePath:process.env.DROPLAN_BROWSER_EXECUTABLE} : {})});
  const context = await browser.newContext({acceptDownloads:true,locale:'en-US',viewport:{width:320,height:760}});
  try {
    const page = await context.newPage(), errors = [], responses = [];
    let fileRequests = 0, nativeDownloads = 0;
    page.on('download', () => nativeDownloads++);
    page.on('pageerror', error => errors.push(error.message));
    page.on('request', request => { if (new URL(request.url()).pathname.startsWith('/api/v1/files/')) fileRequests++; });
    page.on('response', async response => {
      if (new URL(response.url()).pathname === '/api/v1/advice' && response.ok()) responses.push(await response.json());
    });
    await page.addInitScript(() => {
      window.showSaveFilePicker = async () => {
        const root = await navigator.storage.getDirectory();
        const handle = await root.getFileHandle('droplan-advice-test.bin',{create:true});
        const writable = await handle.createWritable();
        return {createWritable:async () => ({write:block => writable.write(block),abort:() => writable.abort(),close:async () => {
          await writable.close();
          const file = await handle.getFile();
          window.savedHash = [...new Uint8Array(await crypto.subtle.digest('SHA-256',await file.arrayBuffer()))]
            .map(value => value.toString(16).padStart(2,'0')).join('');
          window.savedBytes = file.size;
        }})};
      };
    });
    await page.goto(fs.readFileSync(process.env.DROPLAN_ENTRY_FILE || '.ai-work/browser-entry.txt','utf8').trim());
    await page.locator('#pair-form button').click();
    await page.locator('#receiver').waitFor({state:'visible'});
    await page.locator('#help > summary').click();
    await page.locator('#advanced-help > summary').click();
    await page.locator('#advanced-files .advanced-transfer summary').first().click();
    await page.locator('#advanced-files .advanced-transfer button').first().click();
    const native = page.waitForEvent('download');
    await page.locator('#files a.download').first().click();
    const download = await native;
    const saved = path.join(out,'native.bin'); await download.saveAs(saved);
    const expected = crypto.createHash('sha256').update(fs.readFileSync(saved)).digest('hex');
    fs.unlinkSync(saved);
    await page.waitForFunction(() => document.querySelector('#files')?.textContent.includes('Sent'));
    await page.locator('#files > li > button').first().click();
    await page.waitForFunction(() => window.savedBytes === 16 * 1024 * 1024, null, {timeout:30000});
    assert.equal(await page.evaluate(() => window.savedHash),expected);
    await page.waitForFunction(() => document.querySelector('#recommendation').textContent.includes('Observed speed'), null, {timeout:20000});
    assert.ok(responses.some(result => result.compared && result.scope === 'current_connection'));
    await page.locator('#connection-details summary').click();
    const before = fileRequests, downloadsBefore = nativeDownloads;
    if (await page.locator('#apply-advice').isVisible()) await page.locator('#apply-advice').click();
    await page.evaluate(() => new Promise(requestAnimationFrame));
    assert.equal(fileRequests,before,'Recommendation must not start or restart a download');
    assert.equal(nativeDownloads,downloadsBefore,'Recommendation must not trigger native download');
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth),false);
    await page.screenshot({path:path.join(out,'advice.png'),fullPage:true});
    await page.evaluate(() => window.dispatchEvent(new Event('offline')));
    assert.equal(await page.locator('#apply-advice').isHidden(),true);
    assert.equal((await page.locator('#recommendation').textContent()).includes('Observed speed'),false);
    assert.deepEqual(errors,[]);
    await page.evaluate(async () => { const root = await navigator.storage.getDirectory(); await root.removeEntry('droplan-advice-test.bin'); });
    fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({desktopOnly:true,engine:'chromium',nativeDownloadBytes:16*1024*1024,
      webSocketOpfsBytes:16*1024*1024,matchingSha256:expected,realServerAdvice:true,networkChangeClearsAdvice:true,
      noAutomaticDownload:true,horizontalOverflow:false,pageErrors:errors,filePicker:'test OPFS adapter; not native picker'},null,2));
    console.log('HTTP/WS real transfers, observed recommendation and network invalidation passed');
  } finally { await context.close(); await browser.close(); }
})().catch(error => { console.error(String(error.message).replace(/https?:\/\/[^\s]+/g,'[private URL]')); process.exitCode=1; });
