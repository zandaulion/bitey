// The beta function's one rule of its own: a listed tester token counts as a
// live subscription. Anything else must still go to Google Play.

import test from 'node:test';
import assert from 'node:assert/strict';
import { isEntitled } from '../core/ai/gate.js';

process.env.GCLOUD_PROJECT = 'demo-bitey-beta';
process.env.FIREBASE_CONFIG = JSON.stringify({ projectId: 'demo-bitey-beta' });
process.env.BETA_TESTER_TOKENS = 'tester.one, tester.two';
const { testerOrPlay, analyseBeta } = await import('../functions/beta.js');

test('a listed tester token is a live Bitey AI subscription', async () => {
  for (const token of ['tester.one', 'tester.two']) {
    const subscription = await testerOrPlay('com.zandaulion.bitey.beta', token);
    assert.equal(subscription.productId, 'bitey_ai');
    assert.equal(subscription.test, true);
    assert.equal(isEntitled(subscription), true);
  }
});

test('an unlisted token is not taken for a tester', async () => {
  // It goes on to Play, which this test cannot reach: what matters is that it
  // is not answered here with a subscription.
  const answer = await testerOrPlay('com.zandaulion.bitey.beta', 'tester.three')
    .then((subscription) => subscription, (err) => err);
  assert.notEqual(answer?.basePlanId, 'tester');
});

test('the beta exports its own function, not production\'s', () => {
  assert.equal(typeof analyseBeta, 'function');
});
