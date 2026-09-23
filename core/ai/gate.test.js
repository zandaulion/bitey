import test from 'node:test';
import assert from 'node:assert/strict';
import {
  validateAnalyseRequest, isEntitled, decideQuota, dayKey, subscriptionFromPlay,
  DAILY_LIMIT, MAX_IMAGE_CHARS
} from './gate.js';

const photo = 'a'.repeat(200);

test('a well-formed request is accepted and normalised', () => {
  const out = validateAnalyseRequest({
    purchaseToken: 'abc.def-ghi~jkl',
    image: photo,
    mimeType: 'image/png',
    correction: '  it is vegetarian  ',
    locale: 'ro-RO'
  });
  assert.equal(out.ok, true);
  assert.equal(out.mimeType, 'image/png');
  assert.equal(out.correction, 'it is vegetarian');
  assert.equal(out.locale, 'ro', 'the region is dropped; the prompt wants a language');
});

test('an unknown mime type is read as JPEG rather than refused', () => {
  // The camera path re-encodes to JPEG, so an odd value here is a client
  // quirk, not an attack. Refusing would cost someone their photograph.
  const out = validateAnalyseRequest({ purchaseToken: 't', image: photo, mimeType: 'image/heic' });
  assert.equal(out.mimeType, 'image/jpeg');
});

test('a request without a purchase token is refused before anything costs money', () => {
  assert.deepEqual(validateAnalyseRequest({ image: photo }), { ok: false, error: 'no_token' });
  assert.deepEqual(validateAnalyseRequest({ purchaseToken: '   ', image: photo }),
    { ok: false, error: 'no_token' });
});

test('a token that could not have come from Play is refused', () => {
  assert.deepEqual(validateAnalyseRequest({ purchaseToken: 'a b', image: photo }),
    { ok: false, error: 'bad_token' });
  assert.deepEqual(validateAnalyseRequest({ purchaseToken: 'x'.repeat(1025), image: photo }),
    { ok: false, error: 'bad_token' });
});

test('an absent or truncated photograph is refused', () => {
  assert.deepEqual(validateAnalyseRequest({ purchaseToken: 't' }), { ok: false, error: 'no_image' });
  assert.deepEqual(validateAnalyseRequest({ purchaseToken: 't', image: 'abc' }),
    { ok: false, error: 'no_image' });
});

test('an oversized photograph is refused before it is decoded', () => {
  const huge = 'a'.repeat(MAX_IMAGE_CHARS + 1);
  assert.deepEqual(validateAnalyseRequest({ purchaseToken: 't', image: huge }),
    { ok: false, error: 'image_too_large' });
});

test('an empty correction is not sent to the model as one', () => {
  const out = validateAnalyseRequest({ purchaseToken: 't', image: photo, correction: '   ' });
  assert.equal(out.correction, null);
});

test('a long correction is capped rather than refused', () => {
  const out = validateAnalyseRequest({ purchaseToken: 't', image: photo, correction: 'x'.repeat(500) });
  assert.equal(out.correction.length, 200);
});

const future = Date.now() + 60_000;
const past = Date.now() - 60_000;

test('an active, unexpired subscription is entitled', () => {
  assert.equal(isEntitled({ state: 'SUBSCRIPTION_STATE_ACTIVE', expiryTimeMillis: future }), true);
});

test('a subscription in its grace period still works', () => {
  // Payment failed and Play is retrying. The customer has not stopped paying,
  // and locking them out over a bank's decline is how you lose them.
  assert.equal(isEntitled({ state: 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD', expiryTimeMillis: future }), true);
});

test('a cancelled subscription works until it expires, and not after', () => {
  assert.equal(isEntitled({ state: 'SUBSCRIPTION_STATE_CANCELED', expiryTimeMillis: future }), true);
  assert.equal(isEntitled({ state: 'SUBSCRIPTION_STATE_CANCELED', expiryTimeMillis: past }), false);
});

test('account hold, pause, expiry and pending are not entitled', () => {
  for (const state of [
    'SUBSCRIPTION_STATE_ON_HOLD', 'SUBSCRIPTION_STATE_PAUSED',
    'SUBSCRIPTION_STATE_EXPIRED', 'SUBSCRIPTION_STATE_PENDING'
  ]) {
    assert.equal(isEntitled({ state, expiryTimeMillis: future }), false, state);
  }
});

test('an active state cannot outlive its own expiry', () => {
  // A replayed or cached verification must not keep a lapsed subscription
  // alive, so the clock decides rather than the status word.
  assert.equal(isEntitled({ state: 'SUBSCRIPTION_STATE_ACTIVE', expiryTimeMillis: past }), false);
});

test('a missing or shapeless subscription is not entitled', () => {
  assert.equal(isEntitled(null), false);
  assert.equal(isEntitled({}), false);
  assert.equal(isEntitled({ state: 'SUBSCRIPTION_STATE_ACTIVE' }), false);
  assert.equal(isEntitled({ state: 'SUBSCRIPTION_STATE_ACTIVE', expiryTimeMillis: 'soon' }), false);
});

test('the daily allowance runs out exactly at the limit', () => {
  assert.deepEqual(decideQuota(0), { allowed: true, used: 0, remaining: DAILY_LIMIT, limit: DAILY_LIMIT });
  assert.equal(decideQuota(DAILY_LIMIT - 1).allowed, true);
  assert.equal(decideQuota(DAILY_LIMIT - 1).remaining, 1);
  assert.equal(decideQuota(DAILY_LIMIT).allowed, false);
  assert.equal(decideQuota(DAILY_LIMIT).remaining, 0);
});

test('a count past the limit cannot report negative readings left', () => {
  assert.deepEqual(decideQuota(DAILY_LIMIT + 5).remaining, 0);
});

test('a missing or nonsense count is treated as none used', () => {
  assert.equal(decideQuota(undefined).used, 0);
  assert.equal(decideQuota(-3).used, 0);
});

// A recorded subscriptionsv2 body, trimmed to the fields this reads.
const playBody = {
  subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE',
  latestOrderId: 'GPA.1234-5678-9012-34567',
  lineItems: [{
    productId: 'bitey_ai',
    expiryTime: '2026-10-23T18:04:12.345Z',
    offerDetails: { basePlanId: 'monthly' }
  }]
};

test('Play\'s subscription body is flattened to the facts the rule uses', () => {
  const out = subscriptionFromPlay(playBody);
  assert.equal(out.state, 'SUBSCRIPTION_STATE_ACTIVE');
  assert.equal(out.productId, 'bitey_ai');
  assert.equal(out.basePlanId, 'monthly');
  assert.equal(out.expiryTimeMillis, Date.parse('2026-10-23T18:04:12.345Z'));
  assert.equal(out.orderId, 'GPA.1234-5678-9012-34567');
});

test('a flattened active subscription is entitled before its expiry, not after', () => {
  const out = subscriptionFromPlay(playBody);
  assert.equal(isEntitled(out, Date.parse('2026-10-01T00:00:00Z')), true);
  assert.equal(isEntitled(out, Date.parse('2026-11-01T00:00:00Z')), false);
});

test('a body without line items yields nothing entitled rather than throwing', () => {
  // If Play ever changes this shape, the failure must be a refusal that shows
  // up in testing, not an exception in production.
  for (const body of [null, {}, { subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE' }, { lineItems: [] }]) {
    const out = subscriptionFromPlay(body);
    assert.equal(out.expiryTimeMillis, null);
    assert.equal(isEntitled(out), false);
  }
});

test('an unparseable expiry does not become an entitlement', () => {
  const out = subscriptionFromPlay({
    subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE',
    lineItems: [{ productId: 'bitey_ai', expiryTime: 'whenever' }]
  });
  assert.equal(out.expiryTimeMillis, null);
  assert.equal(isEntitled(out), false);
});

test('the day is keyed in UTC', () => {
  assert.equal(dayKey(new Date('2026-09-23T23:59:59Z')), '2026-09-23');
  assert.equal(dayKey(new Date('2026-09-24T00:00:01Z')), '2026-09-24');
});
