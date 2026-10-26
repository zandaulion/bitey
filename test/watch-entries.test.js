// The watch logs entries the page built ahead of time (entryTemplate), which
// the phone only stamps with an id and the time. These check that a template
// is the row saveEntry writes, less those fields, so nothing logged from the
// wrist differs from the same tap on the phone.

import test from 'node:test';
import assert from 'node:assert/strict';
import { register } from 'node:module';

register('./fixtures/web-root-loader.mjs', import.meta.url);
globalThis.window = {};
const { entryTemplate } = await import('../web/local-api.js');
const { createQuickBiteItem, QUICK_BITES, quickBiteBody, recentBiteItem } = await import('../core/foods.js');
const { totalsOf } = await import('../core/analysis/estimate.js');

test('a quick bite template carries the totals the tray would save', () => {
  for (const preset of QUICK_BITES) {
    const body = quickBiteBody(createQuickBiteItem(preset));
    const entry = entryTemplate(body);
    assert.deepEqual(entry.totals, totalsOf({ items: body.items, portionSource: 'estimated' }));
    assert.equal(Math.round(entry.totals.calories), preset.calories, preset.id);
    assert.equal(entry.meal, 'snack');
    assert.equal(entry.note, 'Quick bite');
    assert.equal(entry.portionSource, 'estimated');
  }
});

test('a recent food logs at its own rates and weight', () => {
  const food = { name: 'Oat biscuit', grams: 25, per: { calories: 4.6, protein: 0.07, fat: 0.2, carbs: 0.62 }, barcode: null };
  const entry = entryTemplate(quickBiteBody(recentBiteItem(food)));
  assert.equal(entry.items.length, 1);
  assert.equal(entry.items[0].name, 'Oat biscuit');
  assert.equal(Math.round(entry.totals.calories), 115);
});

test('a template holds nothing the phone must stamp', () => {
  const entry = entryTemplate(quickBiteBody(createQuickBiteItem(QUICK_BITES[0])));
  for (const key of ['id', 'day', 'createdAt', 'sortKey', 'photoId', 'photoData']) {
    assert.equal(key in entry, false, key);
  }
});

test('a template sanitises what saveEntry sanitises', () => {
  const items = quickBiteBody(createQuickBiteItem(QUICK_BITES[1])).items;
  const entry = entryTemplate({ items, meal: 'elevenses', note: 'x'.repeat(900), portionConfirmed: false });
  assert.equal(entry.meal, null);
  assert.equal(entry.note.length, 500);
  assert.equal(entry.portionSource, 'model');
  assert.equal(entry.photoMimeType, null);
});
