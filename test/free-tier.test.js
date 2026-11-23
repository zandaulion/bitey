// The free tier: its rules in core/ai/gate.js, and the counters that enforce
// them in functions/quota.js, run against an in-memory stand-in for Firestore.

import test from 'node:test';
import assert from 'node:assert/strict';
import {
  FREE_DEFAULTS, normaliseFreeConfig, decideFreeQuota, poolAlerts, resetsAt, validateAnalyseRequest
} from '../core/ai/gate.js';
import { claim, refund, claimFree, refundFree, freeConfig, freeIdFor, entitlementIdFor } from '../functions/quota.js';

/** Just enough of Firestore for quota.js: documents by path, transactions
 * that read and then write. */
function fakeFirestore(seed = {}) {
  const docs = new Map(Object.entries(seed));
  const ref = (path) => ({
    path,
    get: async () => ({ exists: docs.has(path), data: () => docs.get(path) })
  });
  return {
    docs,
    collection: (name) => ({ doc: (id) => ref(`${name}/${id}`) }),
    doc: (path) => ref(path),
    runTransaction: async (work) => {
      const writes = [];
      const tx = {
        get: async (r) => r.get(),
        set: (r, data, { merge } = {}) => writes.push([r.path, data, merge])
      };
      const out = await work(tx);
      for (const [path, data, merge] of writes) {
        docs.set(path, merge ? { ...(docs.get(path) || {}), ...data } : data);
      }
      return out;
    }
  };
}

const NOW = new Date('2026-11-22T10:00:00Z');
const config = { readings: 5, corrections: 1, poolLimit: 1000, alertAt: 0.8 };
const free = (db, options = {}) => claimFree('u1', { config, now: NOW, db, ...options });

test('the free tier defaults are 2 readings, 1 correction and a pool of 1,000', () => {
  assert.deepEqual({ ...FREE_DEFAULTS }, { readings: 2, corrections: 1, poolLimit: 1000, alertAt: 0.8 });
  assert.deepEqual(normaliseFreeConfig(null), { ...FREE_DEFAULTS });
});

test('each setting falls back on its own when it makes no sense', () => {
  assert.deepEqual(normaliseFreeConfig({ readings: 8, corrections: -1, poolLimit: 'lots', alertAt: 3 }),
    { readings: 8, corrections: 1, poolLimit: 1000, alertAt: 0.8 });
  assert.equal(normaliseFreeConfig({ readings: 2.5 }).readings, FREE_DEFAULTS.readings);
  assert.equal(normaliseFreeConfig({ poolLimit: 0 }).poolLimit, 0, '0 switches the free tier off');
});

test('readings, corrections and the pool are decided apart', () => {
  assert.equal(decideFreeQuota({ readingsUsed: 4, config }).allowed, true);
  assert.equal(decideFreeQuota({ readingsUsed: 5, config }).reason, 'free_daily_limit');
  assert.equal(decideFreeQuota({ readingsUsed: 5, correction: true, config }).allowed, true,
    'a correction does not need a reading left');
  assert.equal(decideFreeQuota({ correctionsUsed: 1, correction: true, config }).reason, 'free_correction_used');
  assert.equal(decideFreeQuota({ poolUsed: 1000, config }).reason, 'free_pool_exhausted');
  assert.equal(decideFreeQuota({ config: { poolLimit: 0 } }).reason, 'free_pool_exhausted');
});

test('the pool alerts at 80% and at the limit', () => {
  assert.deepEqual(poolAlerts(798, 799, config), []);
  assert.deepEqual(poolAlerts(799, 800, config), ['alert']);
  assert.deepEqual(poolAlerts(999, 1000, config), ['limit']);
  assert.deepEqual(poolAlerts(0, 1, { poolLimit: 1, alertAt: 0.8 }), ['alert', 'limit']);
});

test('allowances come back at midnight UTC', () => {
  assert.equal(resetsAt(NOW), '2026-11-23T00:00:00.000Z');
  assert.equal(resetsAt(new Date('2026-11-22T23:59:59Z')), '2026-11-23T00:00:00.000Z');
});

test('a request needs a purchase token or a Firebase ID token, and keeps its request id', () => {
  const photo = { image: 'x'.repeat(200) };
  assert.equal(validateAnalyseRequest(photo).error, 'no_token');
  assert.equal(validateAnalyseRequest({ ...photo, idToken: 'not a jwt' }).error, 'bad_id_token');
  const ok = validateAnalyseRequest({ ...photo, idToken: 'aaa.bbb.ccc', requestId: 'reading-12345678' });
  assert.equal(ok.ok, true);
  assert.equal(ok.idToken, 'aaa.bbb.ccc');
  assert.equal(ok.purchaseToken, null);
  assert.equal(ok.requestId, 'reading-12345678');
  assert.equal(validateAnalyseRequest({ ...photo, idToken: 'a.b.c', requestId: 'x' }).requestId, null);
});

test('a free user gets five readings, then is refused', async () => {
  const db = fakeFirestore();
  for (let i = 4; i >= 0; i--) {
    const out = await free(db);
    assert.equal(out.allowed, true);
    assert.equal(out.remaining, i);
  }
  const sixth = await free(db);
  assert.equal(sixth.allowed, false);
  assert.equal(sixth.reason, 'free_daily_limit');
  assert.equal(db.docs.get('ai_usage_free_pool/2026-11-22').count, 5);
});

test('one correction a day, on top of the readings', async () => {
  const db = fakeFirestore();
  for (let i = 0; i < 5; i++) await free(db);
  const fix = await free(db, { correction: true });
  assert.equal(fix.allowed, true);
  assert.equal(fix.correctionsRemaining, 0);
  assert.equal((await free(db, { correction: true })).reason, 'free_correction_used');
  assert.equal(db.docs.get('ai_usage_free/u1_2026-11-22').corrections, 1);
});

test('the same request id is charged once', async () => {
  const db = fakeFirestore();
  await free(db, { requestId: 'reading-aaaaaaaa' });
  const retry = await free(db, { requestId: 'reading-aaaaaaaa' });
  assert.equal(retry.allowed, true);
  assert.equal(retry.repeat, true);
  assert.equal(db.docs.get('ai_usage_free/u1_2026-11-22').readings, 1);
  assert.equal(db.docs.get('ai_usage_free_pool/2026-11-22').count, 1);
});

test('the pool refuses everyone once full, and alerts once each', async () => {
  const db = fakeFirestore({ 'ai_usage_free_pool/2026-11-22': { count: 799 } });
  const crossing = await free(db);
  assert.deepEqual(crossing.alerts, ['alert']);
  assert.deepEqual((await claimFree('u2', { config, now: NOW, db })).alerts, [], 'the alert fires once');
  db.docs.set('ai_usage_free_pool/2026-11-22', { ...db.docs.get('ai_usage_free_pool/2026-11-22'), count: 1000 });
  assert.equal((await claimFree('u3', { config, now: NOW, db })).reason, 'free_pool_exhausted');
});

test('a refund gives the reading back to the person and the pool, and forgets the id', async () => {
  const db = fakeFirestore();
  await free(db, { requestId: 'reading-bbbbbbbb' });
  await refundFree('u1', { requestId: 'reading-bbbbbbbb', now: NOW, db });
  assert.equal(db.docs.get('ai_usage_free/u1_2026-11-22').readings, 0);
  assert.equal(db.docs.get('ai_usage_free_pool/2026-11-22').count, 0);
  assert.equal((await free(db, { requestId: 'reading-bbbbbbbb' })).repeat, undefined, 'charged afresh');
});

test('a paid retry with the same request id is charged once', async () => {
  const db = fakeFirestore();
  await claim('p1', { requestId: 'reading-cccccccc', now: NOW, db });
  const retry = await claim('p1', { requestId: 'reading-cccccccc', now: NOW, db });
  assert.equal(retry.repeat, true);
  assert.equal(retry.remaining, 19);
  assert.equal(db.docs.get('ai_usage/p1_2026-11-22').count, 1);
  await refund('p1', { requestId: 'reading-cccccccc', now: NOW, db });
  assert.equal(db.docs.get('ai_usage/p1_2026-11-22').count, 0);
});

test('the settings document is read, then cached for a minute', async () => {
  const db = fakeFirestore({ 'test_config/a': { readings: 7 } });
  assert.equal((await freeConfig({ path: 'test_config/a', db, now: 0 })).readings, 7);
  db.docs.set('test_config/a', { readings: 9 });
  assert.equal((await freeConfig({ path: 'test_config/a', db, now: 30000 })).readings, 7);
  assert.equal((await freeConfig({ path: 'test_config/a', db, now: 61000 })).readings, 9);
  assert.equal((await freeConfig({ path: 'test_config/missing', db, now: 0 })).readings, FREE_DEFAULTS.readings);
});

test('a free user and a subscriber never share a counter', () => {
  assert.notEqual(freeIdFor('abc'), entitlementIdFor('abc'));
});
