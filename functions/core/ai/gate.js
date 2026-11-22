// GENERATED COPY of core/ai/gate.js -- do not edit.
// Run: node scripts/sync-functions-core.mjs
// What has to be true before a photograph reaches the model.
//
// This is deliberately pure: no Firestore, no Play API, no network. The
// hosting glue in functions/ supplies the facts -- is the subscription live,
// how many calls have been made today -- and this decides. Keeping the
// decision here means it is testable in the ordinary suite, and that the rule
// is written once rather than restated in each place that enforces it.

/**
 * Calls per subscription, per day.
 *
 * Twenty is what Bitey AI is sold as in the Play listing ("20 meal analyses
 * every day"), so it is a promise rather than a tuning knob: it may be raised
 * without telling anyone, but never quietly lowered.
 *
 * Note this is stricter than the PWA's own per-account limit in server/
 * budget.js, which covers friends and family on somebody's own hardware. Here
 * a stranger controls the timing and a third party sends the bill.
 */
export const DAILY_LIMIT = 20;

/**
 * The free tier, used when the settings document (config/free_tier in
 * Firestore) is missing or holds a value that makes no sense. Every number
 * here can be changed in that document without a deploy; see
 * normaliseFreeConfig.
 *
 * - readings: photo, typed and leftovers readings a day, per free user.
 * - corrections: corrections a day, per free user, apart from the readings.
 * - poolLimit: free readings and corrections a day across the whole app. Past
 *   it free users are told readings are used up; paying users are unaffected.
 *   0 switches the free tier off.
 * - alertAt: the share of poolLimit at which an alert is logged once a day.
 */
export const FREE_DEFAULTS = Object.freeze({
  readings: 5,
  corrections: 1,
  poolLimit: 1000,
  alertAt: 0.8
});

/** The settings document's values, each checked, each falling back to its
 * default on its own: one bad field must not take the others with it. */
export function normaliseFreeConfig(doc) {
  const whole = (value, fallback) =>
    Number.isInteger(value) && value >= 0 && value <= 1000000 ? value : fallback;
  const share = Number(doc?.alertAt);
  return {
    readings: whole(doc?.readings, FREE_DEFAULTS.readings),
    corrections: whole(doc?.corrections, FREE_DEFAULTS.corrections),
    poolLimit: whole(doc?.poolLimit, FREE_DEFAULTS.poolLimit),
    alertAt: Number.isFinite(share) && share > 0 && share <= 1 ? share : FREE_DEFAULTS.alertAt
  };
}

/** When today's allowance comes back: the next midnight UTC (see dayKey). */
export const resetsAt = (now = new Date()) => {
  const next = new Date(now);
  next.setUTCHours(24, 0, 0, 0);
  return next.toISOString();
};

/**
 * Whether a free user's request fits today. A correction (a photo sent with
 * the person's own words) spends the correction allowance; everything else,
 * leftovers included, spends a reading. Both spend from the app-wide pool.
 */
export function decideFreeQuota({ readingsUsed = 0, correctionsUsed = 0, poolUsed = 0, correction = false, config }) {
  const c = normaliseFreeConfig(config);
  const counter = correction ? 'corrections' : 'readings';
  const remainingReadings = Math.max(0, c.readings - readingsUsed);
  const remainingCorrections = Math.max(0, c.corrections - correctionsUsed);
  const left = { remaining: remainingReadings, correctionsRemaining: remainingCorrections, limit: c.readings };
  if (poolUsed >= c.poolLimit) return { allowed: false, reason: 'free_pool_exhausted', counter, ...left };
  if (correction && remainingCorrections <= 0) return { allowed: false, reason: 'free_correction_used', counter, ...left };
  if (!correction && remainingReadings <= 0) return { allowed: false, reason: 'free_daily_limit', counter, ...left };
  return {
    allowed: true,
    counter,
    remaining: correction ? remainingReadings : remainingReadings - 1,
    correctionsRemaining: correction ? remainingCorrections - 1 : remainingCorrections,
    limit: c.readings
  };
}

/** The alert levels a pool count crosses on its way from `before` to
 * `after`: "alert" at alertAt of the limit, "limit" at the limit itself. */
export function poolAlerts(before, after, config) {
  const c = normaliseFreeConfig(config);
  if (c.poolLimit <= 0) return [];
  const levels = [];
  const alertLine = Math.ceil(c.poolLimit * c.alertAt);
  if (before < alertLine && after >= alertLine) levels.push('alert');
  if (before < c.poolLimit && after >= c.poolLimit) levels.push('limit');
  return levels;
}

/** Largest photograph accepted, before base64 expansion. Matches the Android
 * capture path, which re-encodes to roughly a few hundred kilobytes. */
export const MAX_IMAGE_BYTES = 5 * 1024 * 1024;

/** Base64 is four characters per three bytes, so this is the ceiling on the
 * encoded string. Checked before decoding: a body that would blow the limit
 * should be refused without first being expanded in memory. */
export const MAX_IMAGE_CHARS = Math.ceil(MAX_IMAGE_BYTES / 3) * 4;
export const MAX_DESCRIPTION_CHARS = 1000;

export function validateDescription(value) {
  if (typeof value !== 'string' || !value.trim()) return { ok: false, error: 'no_description' };
  if (value.length > MAX_DESCRIPTION_CHARS) return { ok: false, error: 'description_too_long' };
  return { ok: true, description: value.trim() };
}

/** Most foods one leftovers reading compares. A plate holds a handful; the
 * cap only bounds what a request can put into the prompt. */
export const MAX_LEFTOVER_ITEMS = 40;

export const ANALYSE_ERRORS = {
  no_token: 'This device has no Bitey AI subscription.',
  bad_token: 'This device has no Bitey AI subscription.',
  no_image: 'No photo was received.',
  no_description: 'Describe what you ate first.',
  description_too_long: 'Keep the meal description to 1,000 characters.',
  bad_mode: 'That analysis mode is not supported.',
  image_too_large: 'That photo is too large to read.',
  no_original: 'The photo of the meal before eating was not received.',
  no_items: 'This entry has no foods to compare.',
  not_entitled: 'Bitey AI is not active on this Google Play account.',
  daily_limit: 'That is all the meal analyses for today. They come back tomorrow.',
  bad_id_token: 'Bitey could not confirm this install. Try again.',
  free_daily_limit: 'That is all the free readings for today.',
  free_correction_used: 'Today\'s free correction has been used.',
  free_pool_exhausted: 'Free readings are used up for today.'
};

/**
 * Checks the shape of a request before anything expensive happens.
 *
 * The purchase token is not validated for content beyond being a plausible
 * opaque string: only Google can say whether it is real, and guessing at its
 * format here would reject tokens whenever Play changes it.
 */
/** Diets the photo prompt knows how to use; kept in step with PROMPT_DIETS. */
export const DIETS = ['vegetarian', 'vegan', 'pescatarian'];

export function validateAnalyseRequest(body) {
  const purchaseToken = typeof body?.purchaseToken === 'string' ? body.purchaseToken.trim() : '';
  // A Firebase ID token from the app's anonymous sign-in: the free tier's
  // identity. A JWT is three base64url parts; only Firebase can say whether it
  // is genuine, so its shape is all that is checked here.
  const idToken = typeof body?.idToken === 'string' ? body.idToken.trim() : '';
  if (!purchaseToken && !idToken) return { ok: false, error: 'no_token' };
  if (purchaseToken && (purchaseToken.length > 1024 || /[^\w.~-]/.test(purchaseToken))) {
    return { ok: false, error: 'bad_token' };
  }
  if (idToken && (idToken.length > 4096 || !/^[\w-]+\.[\w-]+\.[\w-]+$/.test(idToken))) {
    return { ok: false, error: 'bad_id_token' };
  }
  // The app's own name for this reading, kept while it retries: the same id
  // twice in a day is charged once (see quota.js).
  const requestId = typeof body?.requestId === 'string' && /^[\w-]{8,80}$/.test(body.requestId)
    ? body.requestId : null;
  const who = { purchaseToken: purchaseToken || null, idToken: idToken || null, requestId };

  if (body?.mode != null && !['analyse', 'leftovers', 'text'].includes(body.mode)) {
    return { ok: false, error: 'bad_mode' };
  }
  if (body?.mode === 'text') {
    const description = validateDescription(body.description);
    if (!description.ok) return description;
    return {
      ...description, mode: 'text', ...who,
      locale: typeof body.locale === 'string' && /^[a-z]{2}(-[A-Za-z]{2})?$/.test(body.locale)
        ? body.locale.slice(0, 2) : 'en'
    };
  }

  const image = typeof body?.image === 'string' ? body.image : '';
  // A hundred characters of base64 is not a photograph by any reading; this
  // catches an empty capture or a truncated upload before it costs anything.
  if (image.length < 100) return { ok: false, error: 'no_image' };
  if (image.length > MAX_IMAGE_CHARS) return { ok: false, error: 'image_too_large' };

  const mimeType = body?.mimeType === 'image/png' ? 'image/png' : 'image/jpeg';
  // A correction is the person disagreeing with the model in their own words.
  // It is passed to the prompt, so it is length-capped here.
  const correction = typeof body?.correction === 'string' && body.correction.trim()
    ? body.correction.trim().slice(0, 200)
    : null;
  const locale = typeof body?.locale === 'string' && /^[a-z]{2}(-[A-Za-z]{2})?$/.test(body.locale)
    ? body.locale.slice(0, 2)
    : 'en';

  // Sent only when the person turned on "Use my diet when reading photos".
  // A closed list, because it is pasted into the prompt: anything else is
  // dropped rather than passed through.
  const diet = DIETS.includes(body?.diet) ? body.diet : null;

  const request = { ok: true, mode: 'analyse', ...who, image, mimeType, correction, locale, diet };
  if (body?.mode !== 'leftovers') return request;

  // Leftovers: `image` is the plate after eating, `original` the same plate
  // before, and `items` what the entry says was served. Both photographs go
  // to the model, so each is held to the same ceiling as a single one.
  const original = typeof body.original === 'string' ? body.original : '';
  if (original.length < 100) return { ok: false, error: 'no_original' };
  if (original.length > MAX_IMAGE_CHARS) return { ok: false, error: 'image_too_large' };
  const items = leftoverItems(body.items);
  if (!items.length) return { ok: false, error: 'no_items' };
  return {
    ...request,
    mode: 'leftovers',
    original,
    originalMimeType: body.originalMimeType === 'image/png' ? 'image/png' : 'image/jpeg',
    items,
    // A leftovers reading has no correction or diet; its prompt has no place
    // for either.
    correction: null,
    diet: null
  };
}

/**
 * The served foods a leftovers prompt lists, reduced to what it uses.
 *
 * They come from the client and go into the prompt, so each is held to a
 * plain shape: an id like the ones estimate.js issues, a name on one line and
 * of a sensible length, and a weight within reason. Anything else is dropped
 * rather than refused -- one odd item should not cost the whole reading.
 */
function leftoverItems(list) {
  if (!Array.isArray(list)) return [];
  const out = [];
  for (const item of list.slice(0, MAX_LEFTOVER_ITEMS)) {
    const id = typeof item?.id === 'string' ? item.id : '';
    if (!/^[\w-]{1,64}$/.test(id)) continue;
    const name = typeof item?.name === 'string'
      ? item.name.replace(/[\u0000-\u001f\u007f]+/g, ' ').trim().slice(0, 80)
      : '';
    const grams = Number(item?.grams);
    if (!name || !Number.isFinite(grams) || grams < 0 || grams > 5000) continue;
    out.push({ id, name, grams: Math.round(grams) });
  }
  return out;
}

/**
 * Whether the subscription Play described may be used right now.
 *
 * Play reports state rather than a verdict, and the difference matters at the
 * edges: a subscription in its grace period is unpaid but still the customer's
 * to use, while one on account hold is not. Expiry is compared against the
 * clock rather than trusted from a status word, because a cached or replayed
 * verification would otherwise outlive the subscription it describes.
 */
export function isEntitled(subscription, now = Date.now()) {
  if (!subscription) return false;
  const { state, expiryTimeMillis } = subscription;
  if (state === 'SUBSCRIPTION_STATE_ON_HOLD' ||
    state === 'SUBSCRIPTION_STATE_PAUSED' ||
    state === 'SUBSCRIPTION_STATE_EXPIRED' ||
    state === 'SUBSCRIPTION_STATE_PENDING') {
    return false;
  }
  const live = state === 'SUBSCRIPTION_STATE_ACTIVE' ||
    state === 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD' ||
    state === 'SUBSCRIPTION_STATE_CANCELED';
  if (!live) return false;
  // A cancelled subscription is paid up to its expiry and keeps working until
  // then; that is what the person bought.
  const expiry = Number(expiryTimeMillis);
  return Number.isFinite(expiry) && expiry > now;
}

/**
 * Play's subscriptionsv2 body, flattened to the facts the rule above uses.
 *
 * Kept here rather than beside the HTTP call so it can be tested against
 * recorded shapes without a Google credential: the mapping is where a change
 * in Play's response would quietly turn every subscription unentitled.
 */
export function subscriptionFromPlay(body) {
  const line = Array.isArray(body?.lineItems) ? body.lineItems[0] : null;
  const expiry = line?.expiryTime ? Date.parse(line.expiryTime) : NaN;
  return {
    state: body?.subscriptionState ?? null,
    expiryTimeMillis: Number.isFinite(expiry) ? expiry : null,
    productId: line?.productId ?? null,
    basePlanId: line?.offerDetails?.basePlanId ?? null,
    // Support and metrics only. Play's own opaque id for the purchase, not a
    // Google account and not a person.
    orderId: body?.latestOrderId ?? null,
    // A licence tester's purchase: free, and on a compressed clock -- a
    // monthly plan renews every few minutes and ends within the hour. Logged
    // so those readings and refusals are not mistaken for customers'.
    test: Boolean(body?.testPurchase)
  };
}

/** The day a call counts against, in UTC. A local day would need a timezone
 * the server does not have and the client must not be trusted to assert. */
export const dayKey = (now = new Date()) => new Date(now).toISOString().slice(0, 10);

/**
 * Whether one more call fits in today's allowance.
 *
 * Returns what is left as well as the verdict, so the client can show how many
 * readings remain rather than only discovering the limit by hitting it.
 */
export function decideQuota(usedToday, limit = DAILY_LIMIT) {
  const used = Number.isFinite(usedToday) && usedToday > 0 ? Math.floor(usedToday) : 0;
  const remaining = Math.max(0, limit - used);
  return { allowed: used < limit, used, remaining, limit };
}
