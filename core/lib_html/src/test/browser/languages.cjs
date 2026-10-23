// 本地夹具上的语言自动选择、切换及窄屏布局；不操作设备。
const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict');
const playwright = require(process.env.DROPLAN_PLAYWRIGHT_MODULE || 'playwright');
(async () => {
  const engine = process.env.DROPLAN_BROWSER || 'chromium';
  const browser = await playwright[engine].launch(process.env.DROPLAN_BROWSER_EXECUTABLE ? { executablePath: process.env.DROPLAN_BROWSER_EXECUTABLE } : {});
  const context = await browser.newContext({ locale: 'ja-JP', viewport: { width: 320, height: 760 } });
  const out = path.resolve(process.env.DROPLAN_EVIDENCE_DIR || '.ai-work/languages-' + engine);
  fs.mkdirSync(out, { recursive: true });
  try {
    const page = await context.newPage(), errors = [];
    page.on('pageerror', e => errors.push(e.message));
    await page.goto(fs.readFileSync(process.env.DROPLAN_ENTRY_FILE || '.ai-work/browser-entry.txt', 'utf8').trim());
    await page.waitForFunction(() => document.documentElement.lang === 'ja');
    await page.locator('#pair-form button').click();
    await page.locator('#receiver').waitFor({ state: 'visible' });
    await page.locator('#help > summary').click();
    await page.locator('#save-help summary').click();
    const languages = ['zh', 'en', 'ja', 'ko', 'de', 'fr', 'es', 'pt-BR'], result = [];
    for (const language of languages) {
      await page.locator('#language').selectOption(language);
      await page.waitForFunction(tag => document.documentElement.lang === tag, language === 'zh' ? 'zh-CN' : language);
      const expected = JSON.parse(fs.readFileSync('core/lib_html/src/main/assets/droplan_web/' + language + '.json', 'utf8'));
      assert.equal(await page.locator('#save-help summary').textContent(), expected.saveLocation);
      assert.equal(await page.locator('h1').textContent(), expected.pair);
      for (const platform of ['apple', 'android', 'desktop']) {
        await page.locator('#save-platform').selectOption(platform);
        assert.equal(await page.locator('.save-locations dd:visible').count(), 1);
        assert.equal(await page.locator('.save-locations dd:visible').getAttribute('data-platform'), platform);
      }
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth);
      assert.equal(overflow, false, language + ': horizontal overflow');
      result.push({ language, locationHelp: true, horizontalOverflow: false });
      if (['ja', 'en', 'pt-BR'].includes(language)) await page.screenshot({ path: path.join(out, language + '.png'), fullPage: true });
    }
    assert.deepEqual(errors, []);
    fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify({ engine, autoDetected: 'ja', languages: result, pageErrors: errors, desktopOnly: true }, null, 2));
    console.log(engine + ': 8 languages and 320px layout passed');
  } finally { await context.close(); await browser.close(); }
})().catch(e => { console.error(String(e.message).replace(/https?:\/\/[^\s]+/g, '[private URL]')); process.exitCode = 1; });
