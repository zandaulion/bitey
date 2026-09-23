// Today's allowance, counted per subscription.
//
// Counting per subscription rather than per device is what makes the limit
// mean anything: a device identifier is free to reissue, and a paid feature
// metered by something the client can regenerate is not metered at all.

import { createHash } from 'node:crypto';
import { getFirestore, FieldValue } from 'firebase-admin/firestore';
import { DAILY_LIMIT, decideQuota, dayKey } from './core/ai/gate.js';

const COLLECTION = 'ai_usage';

/**
 * A stable, non-reversible name for one subscription.
 *
 * The purchase token itself is never stored. It is a bearer credential for the
 * feature, and a counter does not need to hold one -- a hash counts just as
 * well and turns the usage table from a store of credentials into a store of
 * numbers. Support can still correlate: the same subscription always hashes
 * the same way.
 */
export const entitlementIdFor = (purchaseToken) =>
  createHash('sha256').update(purchaseToken).digest('hex').slice(0, 32);

/**
 * Claims one call against today's allowance, or refuses.
 *
 * Charged before the model is called, never after: a call that fails still
 * cost money, and inducing failures must not become the cheap way to loop.
 * The read and the write are one transaction, so two photographs arriving
 * together cannot both see the same count and both decide there was room.
 */
export async function claim(entitlementId, now = new Date(), db = getFirestore()) {
  const day = dayKey(now);
  const ref = db.collection(COLLECTION).doc(`${entitlementId}_${day}`);

  return db.runTransaction(async (tx) => {
    const snapshot = await tx.get(ref);
    const used = snapshot.exists ? Number(snapshot.data()?.count) || 0 : 0;
    const verdict = decideQuota(used, DAILY_LIMIT);
    if (!verdict.allowed) return verdict;

    tx.set(ref, {
      count: used + 1,
      day,
      // Lets a scheduled cleanup drop old rows without reading the key apart.
      updatedAt: FieldValue.serverTimestamp()
    }, { merge: true });

    return { ...verdict, used: used + 1, remaining: verdict.remaining - 1 };
  });
}

/**
 * Gives back a call that never reached the model.
 *
 * Only for failures on our side of the line -- the model unreachable, the
 * request refused upstream. A photograph the model read and disliked has been
 * paid for and stays counted, or "not food" becomes an unlimited retry.
 */
export async function refund(entitlementId, now = new Date(), db = getFirestore()) {
  const day = dayKey(now);
  const ref = db.collection(COLLECTION).doc(`${entitlementId}_${day}`);
  await db.runTransaction(async (tx) => {
    const snapshot = await tx.get(ref);
    if (!snapshot.exists) return;
    const used = Number(snapshot.data()?.count) || 0;
    if (used <= 0) return;
    tx.set(ref, { count: used - 1, day, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
  });
}
