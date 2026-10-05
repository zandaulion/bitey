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

/** Largest photograph accepted, before base64 expansion. Matches the Android
 * capture path, which re-encodes to roughly a few hundred kilobytes. */
export const MAX_IMAGE_BYTES = 5 * 1024 * 1024;

/** Base64 is four characters per three bytes, so this is the ceiling on the
 * encoded string. Checked before decoding: a body that would blow the limit
 * should be refused without first being expanded in memory. */
export const MAX_IMAGE_CHARS = Math.ceil(MAX_IMAGE_BYTES / 3) * 4;

/** Most foods one leftovers reading compares. A plate holds a handful; the
 * cap only bounds what a request can put into the prompt. */
export const MAX_LEFTOVER_ITEMS = 40;

export const ANALYSE_ERRORS = {
  no_token: 'This device has no Bitey AI subscription.',
  bad_token: 'This device has no Bitey AI subscription.',
  no_image: 'No photo was received.',
  image_too_large: 'That photo is too large to read.',
  no_original: 'The photo of the meal before eating was not received.',
  no_items: 'This entry has no foods to compare.',
  not_entitled: 'Bitey AI is not active on this Google Play account.',
  daily_limit: 'That is all the photo readings for today. They come back tomorrow.'
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
  if (!purchaseToken) return { ok: false, error: 'no_token' };
  if (purchaseToken.length > 1024 || /[^\w.~-]/.test(purchaseToken)) {
    return { ok: false, error: 'bad_token' };
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

  const request = { ok: true, mode: 'analyse', purchaseToken, image, mimeType, correction, locale, diet };
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
    orderId: body?.latestOrderId ?? null
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
