// Today's allowance: per subscription for Bitey AI, per install for the free
// tier, and for the free tier a pool shared by the whole app.
//
// Counting per subscription rather than per device is what makes the paid
// limit mean anything: a device identifier is free to reissue, and a paid
// feature metered by something the client can regenerate is not metered at
// all. The free tier has no purchase to count against, so it counts per
// anonymous Firebase user -- which a reinstall does reissue. That is why it
// also has the pool: whatever happens with reinstalls, the free tier's daily
// cost has a ceiling.

import { createHash } from 'node:crypto';
import { getFirestore, FieldValue } from 'firebase-admin/firestore';
import { DAILY_LIMIT, decideQuota, decideFreeQuota, normaliseFreeConfig, poolAlerts, dayKey } from './core/ai/gate.js';

/** Production's collections. The beta passes its own (functions/beta.js). */
export const COLLECTIONS = Object.freeze({
  paid: 'ai_usage',
  free: 'ai_usage_free',
  pool: 'ai_usage_free_pool',
  freeConfig: 'config/free_tier'
});

/**
 * How long a day's counter is kept.
 *
 * Only today's counter is ever read; older ones are kept briefly for support
 * ("why was I told I had run out?") and then deleted by Firestore's TTL
 * policy on `expireAt`. The privacy notice states this period, so change the
 * two together.
 */
const RETENTION_DAYS = 7;

const expiryFor = (day) => new Date(Date.parse(`${day}T00:00:00Z`) + (RETENTION_DAYS + 1) * 86400000);

/**
 * Request ids remembered per counter, so a retry is not charged twice. A
 * phone retries one reading at a time, so the last few dozen are plenty, and
 * the cap keeps the document small however many readings a day brings.
 */
const REMEMBERED_REQUESTS = 50;
const remember = (list, requestId) =>
  requestId ? [...(Array.isArray(list) ? list : []), requestId].slice(-REMEMBERED_REQUESTS) : list ?? [];
const forget = (list, requestId) =>
  Array.isArray(list) ? list.filter((id) => id !== requestId) : [];

/**
 * A stable, non-reversible name for one subscription, or for one free user.
 *
 * The purchase token itself is never stored. It is a bearer credential for the
 * feature, and a counter does not need to hold one -- a hash counts just as
 * well and turns the usage table from a store of credentials into a store of
 * numbers. Support can still correlate: the same subscription always hashes
 * the same way. The free tier's Firebase user id is hashed the same way, so
 * the usage table never names a Firebase account either.
 */
export const entitlementIdFor = (purchaseToken) =>
  createHash('sha256').update(purchaseToken).digest('hex').slice(0, 32);

export const freeIdFor = (uid) => entitlementIdFor(`free:${uid}`);

/**
 * Claims one call against today's allowance, or refuses.
 *
 * Charged before the model is called, never after: a call that fails still
 * cost money, and inducing failures must not become the cheap way to loop.
 * The read and the write are one transaction, so two photographs arriving
 * together cannot both see the same count and both decide there was room.
 *
 * A requestId already charged today is let through without a second charge:
 * the phone retried because the answer was lost on the way back, and the
 * person should not pay twice for one reading.
 */
export async function claim(entitlementId, {
  requestId = null, collection = COLLECTIONS.paid, now = new Date(), db = getFirestore()
} = {}) {
  const day = dayKey(now);
  const ref = db.collection(collection).doc(`${entitlementId}_${day}`);

  return db.runTransaction(async (tx) => {
    const snapshot = await tx.get(ref);
    const data = snapshot.exists ? snapshot.data() ?? {} : {};
    const used = Number(data.count) || 0;
    if (requestId && data.requests?.includes(requestId)) {
      return { allowed: true, repeat: true, used, remaining: Math.max(0, DAILY_LIMIT - used), limit: DAILY_LIMIT };
    }
    const verdict = decideQuota(used, DAILY_LIMIT);
    if (!verdict.allowed) return verdict;

    tx.set(ref, {
      count: used + 1,
      requests: remember(data.requests, requestId),
      day,
      updatedAt: FieldValue.serverTimestamp(),
      // Firestore deletes the document after this time, once the TTL policy
      // on ai_usage.expireAt is enabled in the console.
      expireAt: expiryFor(day)
    }, { merge: true });

    return { ...verdict, used: used + 1, remaining: verdict.remaining - 1 };
  });
}

/**
 * Gives back a call that never reached the model.
 *
 * Only for failures on our side of the line -- the model unreachable, the
 * request refused upstream. A photograph the model read and disliked has been
 * paid for and stays counted, or "not food" becomes an unlimited retry. Its
 * request id is forgotten too, so the retry is charged as the first try was.
 */
export async function refund(entitlementId, {
  requestId = null, collection = COLLECTIONS.paid, now = new Date(), db = getFirestore()
} = {}) {
  const day = dayKey(now);
  const ref = db.collection(collection).doc(`${entitlementId}_${day}`);
  await db.runTransaction(async (tx) => {
    const snapshot = await tx.get(ref);
    if (!snapshot.exists) return;
    const data = snapshot.data() ?? {};
    const used = Number(data.count) || 0;
    if (used <= 0) return;
    tx.set(ref, {
      count: used - 1,
      requests: forget(data.requests, requestId),
      day, updatedAt: FieldValue.serverTimestamp(), expireAt: expiryFor(day)
    }, { merge: true });
  });
}

/**
 * The free tier's settings, re-read at most once a minute per instance, so an
 * edit in the Firebase console takes effect within a minute and a busy
 * instance does not read the document on every request. A missing document,
 * or a failed read, means the defaults in gate.js.
 */
const CONFIG_TTL_MS = 60000;
const configCache = new Map();

export async function freeConfig({ path = COLLECTIONS.freeConfig, db = getFirestore(), now = Date.now() } = {}) {
  const cached = configCache.get(path);
  if (cached && now - cached.at < CONFIG_TTL_MS) return cached.config;
  let doc = null;
  try {
    const snapshot = await db.doc(path).get();
    doc = snapshot.exists ? snapshot.data() : null;
  } catch {
    // The last known settings beat the defaults if there are any.
    if (cached) return cached.config;
  }
  const config = normaliseFreeConfig(doc);
  configCache.set(path, { at: now, config });
  return config;
}

/**
 * Claims one free reading or correction, against the person's own allowance
 * and the app's pool together, in one transaction.
 *
 * Returns the verdict and the pool alerts this claim crossed ("alert",
 * "limit"); each fires once a day, because the pool document remembers it.
 */
export async function claimFree(freeId, {
  correction = false, requestId = null, config,
  collections = COLLECTIONS, now = new Date(), db = getFirestore()
} = {}) {
  const day = dayKey(now);
  const userRef = db.collection(collections.free).doc(`${freeId}_${day}`);
  const poolRef = db.collection(collections.pool).doc(day);

  return db.runTransaction(async (tx) => {
    const [userSnap, poolSnap] = await Promise.all([tx.get(userRef), tx.get(poolRef)]);
    const user = userSnap.exists ? userSnap.data() ?? {} : {};
    const pool = poolSnap.exists ? poolSnap.data() ?? {} : {};
    const readingsUsed = Number(user.readings) || 0;
    const correctionsUsed = Number(user.corrections) || 0;
    const poolUsed = Number(pool.count) || 0;

    if (requestId && user.requests?.includes(requestId)) {
      const c = normaliseFreeConfig(config);
      return {
        allowed: true, repeat: true, counter: correction ? 'corrections' : 'readings', alerts: [],
        remaining: Math.max(0, c.readings - readingsUsed),
        correctionsRemaining: Math.max(0, c.corrections - correctionsUsed),
        limit: c.readings,
        correctionsLimit: c.corrections
      };
    }

    const verdict = decideFreeQuota({ readingsUsed, correctionsUsed, poolUsed, correction, config });
    if (!verdict.allowed) return { ...verdict, alerts: [] };

    const alerts = poolAlerts(poolUsed, poolUsed + 1, config)
      .filter((level) => !pool[`alerted_${level}`]);
    const expireAt = expiryFor(day);
    tx.set(userRef, {
      readings: readingsUsed + (verdict.counter === 'readings' ? 1 : 0),
      corrections: correctionsUsed + (verdict.counter === 'corrections' ? 1 : 0),
      requests: remember(user.requests, requestId),
      day, updatedAt: FieldValue.serverTimestamp(), expireAt
    }, { merge: true });
    tx.set(poolRef, {
      count: poolUsed + 1,
      ...Object.fromEntries(alerts.map((level) => [`alerted_${level}`, true])),
      day, updatedAt: FieldValue.serverTimestamp(), expireAt
    }, { merge: true });

    return { ...verdict, alerts, poolUsed: poolUsed + 1 };
  });
}

/**
 * What is left of a subscription's day, without claiming anything.
 */
export async function peek(entitlementId, {
  collection = COLLECTIONS.paid, now = new Date(), db = getFirestore()
} = {}) {
  const snapshot = await db.collection(collection).doc(`${entitlementId}_${dayKey(now)}`).get();
  const used = snapshot.exists ? Number(snapshot.data()?.count) || 0 : 0;
  return { used, remaining: Math.max(0, DAILY_LIMIT - used), limit: DAILY_LIMIT };
}

/**
 * What is left of a free user's day, without claiming anything. A full pool
 * reads as nothing left: the next reading would be refused for it, so the
 * counter should say so rather than promise one.
 */
export async function peekFree(freeId, {
  config, collections = COLLECTIONS, now = new Date(), db = getFirestore()
} = {}) {
  const day = dayKey(now);
  const [userSnap, poolSnap] = await Promise.all([
    db.collection(collections.free).doc(`${freeId}_${day}`).get(),
    db.collection(collections.pool).doc(day).get()
  ]);
  const user = userSnap.exists ? userSnap.data() ?? {} : {};
  const poolUsed = poolSnap.exists ? Number(poolSnap.data()?.count) || 0 : 0;
  const c = normaliseFreeConfig(config);
  const poolFull = poolUsed >= c.poolLimit;
  return {
    remaining: poolFull ? 0 : Math.max(0, c.readings - (Number(user.readings) || 0)),
    correctionsRemaining: poolFull ? 0 : Math.max(0, c.corrections - (Number(user.corrections) || 0)),
    limit: c.readings,
    correctionsLimit: c.corrections,
    poolFull
  };
}

/** Gives back a free reading or correction that never reached the model,
 * to the person and to the pool. */
export async function refundFree(freeId, {
  counter = 'readings', requestId = null,
  collections = COLLECTIONS, now = new Date(), db = getFirestore()
} = {}) {
  const day = dayKey(now);
  const userRef = db.collection(collections.free).doc(`${freeId}_${day}`);
  const poolRef = db.collection(collections.pool).doc(day);
  await db.runTransaction(async (tx) => {
    const [userSnap, poolSnap] = await Promise.all([tx.get(userRef), tx.get(poolRef)]);
    if (!userSnap.exists) return;
    const user = userSnap.data() ?? {};
    const used = Number(user[counter]) || 0;
    if (used <= 0) return;
    const expireAt = expiryFor(day);
    tx.set(userRef, {
      [counter]: used - 1,
      requests: forget(user.requests, requestId),
      day, updatedAt: FieldValue.serverTimestamp(), expireAt
    }, { merge: true });
    const poolUsed = poolSnap.exists ? Number(poolSnap.data()?.count) || 0 : 0;
    if (poolUsed > 0) {
      tx.set(poolRef, { count: poolUsed - 1, day, updatedAt: FieldValue.serverTimestamp(), expireAt }, { merge: true });
    }
  });
}
