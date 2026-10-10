// Documentation captures of the real app UI with synthetic, in-memory data.
// This harness never contacts a service or changes the shipped native bridge.
// Run with Playwright installed, or set PLAYWRIGHT_MODULE to its module path.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const root = path.resolve(__dirname, '..');
const output = path.join(root, 'store/screenshots');
const types = { '.js': 'text/javascript', '.html': 'text/html', '.css': 'text/css',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png' };

async function capture(browser, viewport, scale, file, extras = false) {
  const context = await browser.newContext({ viewport, deviceScaleFactor: scale,
    colorScheme: 'light', locale: 'en-US', timezoneId: 'UTC', serviceWorkers: 'block' });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.route('**/*', async route => {
    const url = new URL(route.request().url());
    if (url.origin !== 'https://bitey.invalid') return route.abort();
    const name = url.pathname === '/' ? '/index.html' : url.pathname;
    const source = path.join(root, name.startsWith('/core/') ? name : 'web' + name);
    if (!source.startsWith(root + path.sep) || !fs.existsSync(source)) {
      return route.fulfill({ status: 404, body: 'Not found' });
    }
    return route.fulfill({ body: fs.readFileSync(source),
      contentType: types[path.extname(source)] || 'application/octet-stream' });
  });
  await page.addInitScript(() => {
    localStorage.setItem('plate-locale', 'en');
    const reply = (callback, id, body) => setTimeout(() => window[callback](id, JSON.stringify(body)), 0);
    window.screenshotAiCalls = 0;
    window.PlateNative = {
      isLocalBuild: () => true,
      aiEntitlement: () => 'inactive',
      setPrimaryActionLabels() {}, setPrimaryActionsVisible() {}, setBackAvailable() {},
      localDiary(operation, payload, id) {
        const result = { ok: true };
        if (operation === 'entries.list') result.entries = [];
        if (operation === 'profile.read') result.profile = null;
        if (operation === 'weights.list') result.weights = [];
        reply('__plateNativeDiaryResult', id, result);
      },
      searchGenericFoods(query, locale, id) {
        reply('__plateNativeGenericFoodSearchResult', id, { ok: true, results: [] });
      },
      // Fixture-only entitlement and model result: no purchase token, billing
      // service or AI endpoint exists in this isolated browser context.
      requestAiAccess(action, id) {
        reply('__plateNativeAiAccessResult', id, { status: 'active' });
      },
      analysePhoto(payload, id) {
        const request = JSON.parse(payload);
        if (request.mode !== 'text') throw Error('Unexpected screenshot request');
        window.screenshotAiCalls++;
        reply('__plateNativeAnalysisResult', id, { httpStatus: 200, body: { estimate: {
          portionSource: 'model',
          items: [
            { id: 'synthetic-eggs', name: 'Eggs', grams: 100, source: 'text',
              per: { calories: 1.46, protein: .126, fat: .106, carbs: .008, fiber: 0 } },
            { id: 'synthetic-cheese', name: 'Cheddar', grams: 30, source: 'text',
              per: { calories: 4, protein: .25, fat: .33, carbs: .013, fiber: 0 } }
          ], note: 'Assumed two large eggs and 30 g cheddar.'
        } } });
      }
    };
  });
  await page.clock.setFixedTime(new Date('2026-01-15T12:00:00Z'));
  await page.goto('https://bitey.invalid/');
  await page.locator('#app').waitFor({ state: 'visible' });
  await page.evaluate(() => document.getElementById('add-manual').click());
  await page.locator('#meal-description').fill('Two eggs and a slice of cheese');
  await page.locator('#meal-description').blur();
  await page.screenshot({ path: path.join(output, file), animations: 'disabled' });

  if (extras) {
    await page.locator('#text-numbers').click();
    for (const [field, value] of Object.entries({ name: 'Example yogurt', grams: '170',
      kcal: '150', protein: '10', fat: '4', carbs: '18', fiber: '0' })) {
      await page.locator('#m-' + field).fill(value);
    }
    await page.locator('#m-fiber').blur();
    await page.evaluate(() => document.getElementById('review-body').scrollTop = 0);
    assert.equal(await page.evaluate(() => window.screenshotAiCalls), 0);
    await page.screenshot({ path: path.join(output, '06-exact-numbers.png'), animations: 'disabled' });
    await page.locator('#entry-back').click();
    await page.locator('#estimate-text').click();
    await page.locator('#ai-text-consent-copy').waitFor({ state: 'visible' });
    await page.locator('#ai-consent-allow').click();
    await page.locator('#review-kcal').waitFor({ state: 'visible' });
    assert.equal(await page.locator('#review-kcal').textContent(), '266');
    await page.screenshot({ path: path.join(output, '05-text-review.png'), animations: 'disabled' });
  }
  assert.deepEqual(errors, []);
  await context.close();
}

(async () => {
  const browser = await chromium.launch({ headless: true });
  try {
    await capture(browser, { width: 360, height: 800 }, 3, '02-manual-entry.png', true);
    await capture(browser, { width: 600, height: 960 }, 2, 'tablet-7-01-manual-entry.png');
    await capture(browser, { width: 800, height: 1280 }, 2, 'tablet-10-01-manual-entry.png');
    console.log('Captured five entry screens from the real UI with synthetic fixtures; all network requests intercepted.');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
