// Asking Google Play whether a purchase token is a live subscription.
//
// The token arrives from the Android app, which got it from the Play Billing
// library. It is a bearer credential for a paid feature: it is verified on
// every call, never cached as a verdict, and never logged.

import { GoogleAuth } from 'google-auth-library';
import { subscriptionFromPlay } from './core/ai/gate.js';

const PUBLISHER = 'https://androidpublisher.googleapis.com/androidpublisher/v3/applications';

/** The subscription the Android client is allowed to present. A token for some
 * other product of some other app is not an entitlement here. */
export const AI_SUBSCRIPTION_ID = 'bitey_ai';

const auth = new GoogleAuth({
  scopes: ['https://www.googleapis.com/auth/androidpublisher']
});

export class PlayError extends Error {
  constructor(code, message, status = 502) {
    super(message);
    this.code = code;
    this.status = status;
  }
}

/**
 * Reads one subscription purchase, reduced to what [isEntitled] needs.
 *
 * subscriptionsv2 is used rather than the older per-product endpoint because
 * it speaks in base plans: Bitey sells `monthly` and `yearly` under one
 * product, and v2 reports the state of the purchase as a whole rather than of
 * a single SKU.
 *
 * A token Google does not recognise comes back 404, which is an answer rather
 * than a failure: it means no subscription, and the caller should say so
 * rather than retry.
 */
export async function readSubscription(packageName, purchaseToken, fetchImpl = fetch) {
  const client = await auth.getClient();
  const { token } = await client.getAccessToken();
  if (!token) throw new PlayError('play_auth', 'Could not authenticate to Google Play.');

  const url = `${PUBLISHER}/${encodeURIComponent(packageName)}` +
    `/purchases/subscriptionsv2/tokens/${encodeURIComponent(purchaseToken)}`;

  let res;
  try {
    res = await fetchImpl(url, {
      headers: { Authorization: `Bearer ${token}` },
      signal: AbortSignal.timeout(15000)
    });
  } catch {
    throw new PlayError('play_unreachable', 'Could not reach Google Play. Try again in a moment.', 503);
  }

  if (res.status === 404 || res.status === 400) return null;
  if (res.status === 401 || res.status === 403) {
    // The service account has not been granted access in Play Console, or the
    // grant was removed. This is a deployment fault, not the customer's.
    throw new PlayError('play_forbidden', 'This server cannot verify Play purchases.', 502);
  }
  if (!res.ok) throw new PlayError('play_error', 'Google Play could not be asked about this purchase.');

  const body = await res.json().catch(() => null);
  if (!body) throw new PlayError('play_error', 'Google Play sent an unreadable answer.');

  return subscriptionFromPlay(body);
}
