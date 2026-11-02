// Asks Apple whether a subscription is live, the counterpart of play.js.
//
// The app sends a transaction id, which is not a secret, so nothing is taken on
// its word: the status comes from Apple's server, and the transaction inside it
// is checked against Apple's root certificate. The library is loaded only for an
// iPhone's request, so Android's path and cold start do not change.

import { readFileSync } from 'node:fs';
import { AI_SUBSCRIPTION_ID } from './play.js';

const BUNDLE_ID = (process.env.PLAY_PACKAGE || 'com.zandaulion.bitey').trim();

/** Apple has no base plans, so each billing period is a product of its own. */
export const APPLE_PRODUCTS = new Set(['bitey_ai_monthly', 'bitey_ai_yearly']);

/**
 * Apple's status numbers, in Play's words, so gate.js decides once for both.
 * Billing retry has no access, like Play's account hold; a revoked purchase was
 * refunded, so it counts as expired.
 */
const STATES = {
  1: 'SUBSCRIPTION_STATE_ACTIVE',
  2: 'SUBSCRIPTION_STATE_EXPIRED',
  3: 'SUBSCRIPTION_STATE_ON_HOLD',
  4: 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD',
  5: 'SUBSCRIPTION_STATE_EXPIRED'
};

export class AppleError extends Error {
  constructor(code, message, status = 503, detail = null) {
    super(message);
    this.code = code;
    this.status = status;
    this.detail = detail;
  }
}

/** The live subscription in a status response, verified, or null. */
export async function subscriptionFromApple(response, verifyTransaction) {
  const items = (response?.data ?? []).flatMap((group) => group.lastTransactions ?? []);
  let best = null;
  for (const item of items) {
    const transaction = await verifyTransaction(item.signedTransactionInfo);
    if (!APPLE_PRODUCTS.has(transaction.productId)) continue;
    const candidate = {
      productId: AI_SUBSCRIPTION_ID,
      state: STATES[item.status] ?? 'SUBSCRIPTION_STATE_EXPIRED',
      expiryTimeMillis: transaction.expiresDate,
      test: transaction.environment !== 'Production',
      // Stable across renewals, so one subscription keeps one daily count.
      accountId: `apple:${transaction.originalTransactionId}`
    };
    if (!best || Number(candidate.expiryTimeMillis) > Number(best.expiryTimeMillis)) best = candidate;
  }
  return best;
}

/** One environment's client and verifier, built from the deployed credentials. */
async function appleClient(library, environment, env) {
  const { AppStoreServerAPIClient, SignedDataVerifier } = library;
  const root = readFileSync(new URL('./apple-root-ca-g3.cer', import.meta.url));
  const client = new AppStoreServerAPIClient(env.key, env.keyId, env.issuerId, BUNDLE_ID, environment);
  const appId = environment === library.Environment.PRODUCTION ? Number(env.appId) : undefined;
  const verifier = new SignedDataVerifier([root], true, environment, BUNDLE_ID, appId);
  return { client, verifier };
}

/**
 * The subscription a transaction belongs to, or null if Apple does not know it.
 *
 * Production first, then the sandbox, as Apple advises: TestFlight purchases only
 * exist in the sandbox, and the server cannot tell in advance which one it has.
 */
export async function readAppleSubscription(transactionId, env, library) {
  if (!/^\d{1,40}$/.test(transactionId)) return null;
  if (!env?.key || !env.keyId || !env.issuerId) {
    throw new AppleError('apple_config', 'This server cannot verify App Store purchases.', 502);
  }
  const apple = library ?? await import('@apple/app-store-server-library');
  for (const environment of [apple.Environment.PRODUCTION, apple.Environment.SANDBOX]) {
    const { client, verifier } = await appleClient(apple, environment, env);
    let response;
    try {
      response = await client.getAllSubscriptionStatuses(transactionId);
    } catch (err) {
      if (err?.httpStatusCode === 404 || err?.httpStatusCode === 400) continue;
      if (err?.httpStatusCode === 401 || err?.httpStatusCode === 403) {
        // A deployment fault, never the customer's. The detail stays in the log.
        throw new AppleError('apple_forbidden', 'This server cannot verify App Store purchases.', 502,
          err.errorMessage ?? null);
      }
      throw new AppleError('apple_unreachable', 'Could not reach the App Store. Try again in a moment.');
    }
    try {
      return await subscriptionFromApple(response, (signed) => verifier.verifyAndDecodeTransaction(signed));
    } catch {
      // A signature that fails is a refusal: never fall through to the sandbox.
      return null;
    }
  }
  return null;
}
