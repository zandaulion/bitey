import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { X509Certificate, createHash } from 'node:crypto';
import { subscriptionFromApple, readAppleSubscription, AppleError } from '../functions/apple.js';
import { isEntitled } from '../functions/core/ai/gate.js';

const env = { key: 'k', keyId: 'id', issuerId: 'issuer', appId: '1' };
const future = Date.now() + 86_400_000;
const transaction = (over = {}) => ({
  productId: 'bitey_ai_monthly', expiresDate: future,
  environment: 'Production', originalTransactionId: '1000', ...over
});
const answer = (status, signed = 'signed') => ({ data: [{ lastTransactions: [{ status, signedTransactionInfo: signed }] }] });
const verify = (over) => async () => transaction(over);

/** Apple's library, with each environment's answer scripted. */
function fakeApple(answers, { verifyFails = false, calls = [] } = {}) {
  return {
    Environment: { PRODUCTION: 'Production', SANDBOX: 'Sandbox' },
    AppStoreServerAPIClient: class {
      constructor(key, keyId, issuerId, bundleId, environment) { this.environment = environment; }
      async getAllSubscriptionStatuses(id) {
        calls.push(this.environment);
        const result = answers[this.environment];
        if (result instanceof Error) throw result;
        return result;
      }
    },
    SignedDataVerifier: class {
      async verifyAndDecodeTransaction() {
        if (verifyFails) throw new Error('bad signature');
        return transaction();
      }
    }
  };
}
const httpError = (status) => Object.assign(new Error('apple'), { httpStatusCode: status });

test('an active subscription is entitled, in Play\'s shape', async () => {
  const subscription = await subscriptionFromApple(answer(1), verify());
  assert.equal(subscription.productId, 'bitey_ai');
  assert.equal(subscription.accountId, 'apple:1000');
  assert.equal(isEntitled(subscription), true);
});

test('grace keeps access; expiry, billing retry and a refund do not', async () => {
  const entitled = async (status) => isEntitled(await subscriptionFromApple(answer(status), verify()));
  assert.equal(await entitled(4), true);
  assert.equal(await entitled(2), false);
  assert.equal(await entitled(3), false);
  assert.equal(await entitled(5), false);
});

test('a status Apple adds later grants nothing', async () => {
  assert.equal(isEntitled(await subscriptionFromApple(answer(99), verify())), false);
});

test('an active status with a past expiry is refused by the clock', async () => {
  const subscription = await subscriptionFromApple(answer(1), verify({ expiresDate: Date.now() - 1000 }));
  assert.equal(isEntitled(subscription), false);
});

test('another product is not Bitey AI', async () => {
  assert.equal(await subscriptionFromApple(answer(1), verify({ productId: 'other' })), null);
});

test('with two subscriptions, the one that lasts longest counts', async () => {
  const response = { data: [{ lastTransactions: [
    { status: 2, signedTransactionInfo: 'old' }, { status: 1, signedTransactionInfo: 'new' }
  ] }] };
  const decode = async (signed) => transaction({ expiresDate: signed === 'new' ? future : Date.now() - 1 });
  assert.equal(isEntitled(await subscriptionFromApple(response, decode)), true);
});

test('a sandbox purchase is marked as a test', async () => {
  const subscription = await subscriptionFromApple(answer(1), verify({ environment: 'Sandbox' }));
  assert.equal(subscription.test, true);
});

test('an id that is not a number never reaches Apple', async () => {
  const calls = [];
  const apple = fakeApple({}, { calls });
  assert.equal(await readAppleSubscription('../inApps', env, apple), null);
  assert.deepEqual(calls, []);
});

test('production is asked first, then the sandbox', async () => {
  const calls = [];
  const apple = fakeApple({ Production: httpError(404), Sandbox: answer(1) }, { calls });
  const subscription = await readAppleSubscription('1000', env, apple);
  assert.deepEqual(calls, ['Production', 'Sandbox']);
  assert.equal(isEntitled(subscription), true);
});

test('an id unknown to both is no subscription, not a failure', async () => {
  const apple = fakeApple({ Production: httpError(404), Sandbox: httpError(404) });
  assert.equal(await readAppleSubscription('1000', env, apple), null);
});

test('a failed signature is refused, and the sandbox is not tried', async () => {
  const calls = [];
  const apple = fakeApple({ Production: answer(1), Sandbox: answer(1) }, { verifyFails: true, calls });
  assert.equal(await readAppleSubscription('1000', env, apple), null);
  assert.deepEqual(calls, ['Production']);
});

test('rejected credentials are a server fault, kept from the client', async () => {
  const apple = fakeApple({ Production: Object.assign(httpError(401), { errorMessage: 'secret detail' }) });
  await assert.rejects(readAppleSubscription('1000', env, apple), (err) => {
    assert.ok(err instanceof AppleError);
    assert.equal(err.status, 502);
    assert.equal(err.message.includes('secret detail'), false);
    return true;
  });
});

test('an outage asks the client to retry', async () => {
  const apple = fakeApple({ Production: new Error('network') });
  await assert.rejects(readAppleSubscription('1000', env, apple), (err) => err.status === 503);
});

test('missing credentials fail before anything is sent', async () => {
  await assert.rejects(readAppleSubscription('1000', {}, fakeApple({})), (err) => err.code === 'apple_config');
});

test('the bundled root really is Apple Root CA - G3', () => {
  const der = readFileSync(new URL('../functions/apple-root-ca-g3.cer', import.meta.url));
  assert.equal(new X509Certificate(der).subject.includes('Apple Root CA - G3'), true);
  assert.equal(createHash('sha256').update(der).digest('hex').slice(0, 16), '63343abfb89a6a03');
});

test('Android requests stay on the Play path', () => {
  const source = readFileSync(new URL('../functions/index.js', import.meta.url), 'utf8');
  assert.match(source, /fromApple = req\.body\?\.platform === 'ios'/);
  assert.match(source, /: await readSubscription\(PLAY_PACKAGE, request\.purchaseToken\)/);
  // The library loads only inside the iPhone path, so a cold start is unchanged.
  assert.equal(/^import .*app-store-server-library/m.test(readFileSync(new URL('../functions/apple.js', import.meta.url), 'utf8')), false);
});
