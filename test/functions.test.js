// The parts of the Bitey AI function that can be tested without a cloud:
// the copied core is honest, and the vision transport behaves.
//
// The function's own handler is not exercised here -- it needs firebase-admin,
// Firestore and a Play credential. Its decisions live in core/ai/gate.js,
// which is tested directly.

import test from 'node:test';
import assert from 'node:assert/strict';
import { sync, SYNCED } from '../scripts/sync-functions-core.mjs';
import { analysePhoto, AnalysisError, getModel } from '../functions/gemini.js';

test('the copies under functions/core match core/', () => {
  const stale = sync({ check: true });
  assert.deepEqual(stale, [],
    `functions/core is out of date: run node scripts/sync-functions-core.mjs (${stale.join(', ')})`);
});

test('every synced file is one with no imports of its own', async () => {
  // A file that grows an import would deploy and then fail at cold start,
  // because its dependency was never copied.
  const fs = await import('node:fs');
  for (const relative of SYNCED) {
    const source = fs.readFileSync(new URL(`../${relative}`, import.meta.url), 'utf8');
    assert.equal(/^\s*import\s/m.test(source), false, `${relative} has an import`);
  }
});

const reply = (text, usage = {}) => ({
  ok: true,
  status: 200,
  json: async () => ({
    candidates: [{ content: { parts: [{ text }] } }],
    usageMetadata: usage
  })
});

const request = (extra = {}) => ({
  apiKey: 'test-key', imageBase64: 'x'.repeat(200), ...extra
});

test('a JSON answer comes back parsed, with its token usage', async () => {
  const out = await analysePhoto(request(), async () =>
    reply('{"items":[{"name":"stew"}]}', { promptTokenCount: 1409, candidatesTokenCount: 120 }));
  assert.deepEqual(out.raw, { items: [{ name: 'stew' }] });
  assert.deepEqual(out.usage, { promptTokens: 1409, outputTokens: 120 });
  assert.equal(out.model, getModel());
});

test('an answer fenced in markdown is still read', async () => {
  const out = await analysePhoto(request(), async () => reply('```json\n{"items":[]}\n```'));
  assert.deepEqual(out.raw, { items: [] });
});

test('the photograph and the prompt are what gets sent, and nothing else', async () => {
  let sent;
  await analysePhoto(request({ correction: 'it is vegetarian', mimeType: 'image/png' }),
    async (_url, options) => { sent = JSON.parse(options.body); return reply('{"items":[]}'); });

  const parts = sent.contents[0].parts;
  assert.equal(parts[0].inline_data.mime_type, 'image/png');
  assert.equal(parts[0].inline_data.data, 'x'.repeat(200));
  assert.match(parts[1].text, /vegetarian/, 'the correction reaches the prompt');
  assert.equal(parts.length, 2, 'no diary, profile or device data travels with the photo');
});

test('without a key the call is refused rather than attempted', async () => {
  await assert.rejects(
    () => analysePhoto(request({ apiKey: '' }), async () => { throw new Error('must not be called'); }),
    (err) => err instanceof AnalysisError && err.code === 'not_configured' && err.status === 503
  );
});

test('an unreachable model is a 503, so the reading can be refunded', async () => {
  await assert.rejects(
    () => analysePhoto(request(), async () => { throw new Error('ETIMEDOUT'); }),
    (err) => err.code === 'upstream_unreachable' && err.status === 503
  );
});

test('being rate limited upstream is passed on as one, not as a failure', async () => {
  await assert.rejects(
    () => analysePhoto(request(), async () => ({ ok: false, status: 429, json: async () => ({}) })),
    (err) => err.code === 'rate_limited' && err.status === 429
  );
});

test('an upstream error carries its detail but stays a 502', async () => {
  await assert.rejects(
    () => analysePhoto(request(), async () => ({
      ok: false, status: 400, json: async () => ({ error: { message: 'bad image' } })
    })),
    (err) => err.code === 'upstream_error' && err.status === 502 && /bad image/.test(err.message)
  );
});

test('an empty or unreadable answer is reported as such', async () => {
  await assert.rejects(
    () => analysePhoto(request(), async () => ({ ok: true, status: 200, json: async () => ({}) })),
    (err) => err.code === 'empty_response'
  );
  await assert.rejects(
    () => analysePhoto(request(), async () => reply('not json at all')),
    (err) => err.code === 'unparseable'
  );
});
