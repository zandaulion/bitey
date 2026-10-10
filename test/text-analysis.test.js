import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { validateAnalyseRequest, MAX_DESCRIPTION_CHARS } from '../core/ai/gate.js';
import { fromModelResponse, rangesOf, setItemGrams, totalsOf, hasTextItems } from '../core/analysis/estimate.js';
import { summariseRecent } from '../core/foods.js';
import { analyseText } from '../functions/gemini.js';

const request = { mode: 'text', purchaseToken: 'synthetic-purchase-token', description: 'Two eggs and cheese' };

test('text requests require a purchase token and a bounded nonempty description', () => {
  assert.equal(validateAnalyseRequest({ ...request, purchaseToken: '' }).error, 'no_token');
  assert.equal(validateAnalyseRequest({ ...request, purchaseToken: 'bad token' }).error, 'bad_token');
  for (const description of ['', '   ', null, {}, 42]) {
    assert.equal(validateAnalyseRequest({ ...request, description }).error, 'no_description');
  }
  assert.equal(validateAnalyseRequest({ ...request, description: 'x'.repeat(MAX_DESCRIPTION_CHARS + 1) }).error, 'description_too_long');
  assert.equal(validateAnalyseRequest({ ...request, mode: 'free-text' }).error, 'bad_mode');
  const out = validateAnalyseRequest({ ...request, description: '  two eggs  ', locale: 'ro',
    image: 'not sent', diet: 'vegan', profile: { weightKg: 80 }, correction: 'not sent' });
  assert.deepEqual(out, { ok: true, mode: 'text', purchaseToken: request.purchaseToken, description: 'two eggs', locale: 'ro' });
});

test('text transport sends only the description and prompt, and uses the nutrition schema', async () => {
  let sent;
  const result = await analyseText({ apiKey: 'synthetic-api-key', description: request.description, locale: 'ro' }, async (_url, options) => {
    sent = JSON.parse(options.body);
    return { ok: true, status: 200, json: async () => ({ candidates: [{ content: { parts: [{ text: '{"is_food":true,"items":[],"note":""}' }] } }] }) };
  });
  assert.equal(result.raw.is_food, true);
  assert.equal(sent.contents[0].parts.length, 2);
  assert.deepEqual(JSON.parse(sent.contents[0].parts[1].text), { meal_description: request.description });
  assert.match(sent.contents[0].parts[0].text, /Romanian/);
  assert.match(sent.contents[0].parts[0].text, /assumptions/);
  assert.ok(sent.contents[0].parts.every(part => !part.inline_data));
  assert.ok(sent.generationConfig.responseSchema.properties.items);
});

test('text estimates rescale but never acquire photo accuracy bands or exact confidence', () => {
  const estimate = fromModelResponse({ items: [{ name: 'Cheddar', grams: 30, calories: 120, protein_g: 7.5, fat_g: 10, carbs_g: 0.5 }], note: 'Assumed 30 g cheddar.' }, 'text');
  assert.ok(hasTextItems(estimate));
  const changed = setItemGrams(estimate, estimate.items[0].id, 45);
  assert.equal(totalsOf(changed).calories, 180);
  for (const range of Object.values(rangesOf(changed))) {
    assert.equal(range.low, null);
    assert.equal(range.high, null);
    assert.equal(range.confidence, 'unmeasured');
  }
  const recent = summariseRecent([{ item: changed.items[0], loggedAt: new Date().toISOString() }]);
  assert.equal(recent[0].nutritionSource, 'text');
});

test('the actual cloud handler gates text requests before any AI call', () => {
  const child = spawnSync(process.execPath, ['--experimental-vm-modules',
    new URL('./fixtures/text-handler-check.mjs', import.meta.url).pathname], { encoding: 'utf8' });
  assert.equal(child.status, 0, child.stderr || child.stdout);
});
