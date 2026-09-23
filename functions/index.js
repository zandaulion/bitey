// Bitey AI's server half.
//
// The Android app has no account and no server of its own, so this is the only
// place where "has this person paid?" can be answered truthfully. The client's
// own answer is useful for drawing the interface and is worth nothing here:
// every call re-verifies the purchase with Google Play before a photograph
// reaches the model.
//
// What crosses this boundary is one photograph and, if the person typed one, a
// correction in their own words. Never the diary, never the profile, never a
// backup. What is stored is a number per subscription per day.

import { onRequest } from 'firebase-functions/v2/https';
import { defineSecret } from 'firebase-functions/params';
import { initializeApp } from 'firebase-admin/app';
import { getAppCheck } from 'firebase-admin/app-check';
import logger from 'firebase-functions/logger';

import { validateAnalyseRequest, isEntitled, ANALYSE_ERRORS } from './core/ai/gate.js';
import { parseResponse } from './core/analysis/prompt.js';
import { fromModelResponse, totalsOf, rangesOf } from './core/analysis/estimate.js';
import { readSubscription, PlayError, AI_SUBSCRIPTION_ID } from './play.js';
import { entitlementIdFor, claim, refund } from './quota.js';
import { analysePhoto, AnalysisError } from './gemini.js';

initializeApp();

const GEMINI_API_KEY = defineSecret('GEMINI_API_KEY');

// Plain environment values rather than Firebase params: neither is a secret,
// and a param -- even one with a default -- must be confirmed interactively at
// every deploy, which a scripted deploy cannot do. Firebase still loads
// functions/.env.<project> into process.env, so either can be overridden
// there without a code change.
const PLAY_PACKAGE = (process.env.PLAY_PACKAGE || 'com.zandaulion.bitey').trim();
// Off until the Android app is registered for App Check and shipping tokens;
// turning it on before that would lock out every paying customer.
const APPCHECK_ENFORCE = process.env.APPCHECK_ENFORCE === 'true';

const fail = (res, status, code, message, extra = {}) =>
  res.status(status).json({ error: code, message, ...extra });

export const analyse = onRequest(
  {
    region: 'europe-west1',
    secrets: [GEMINI_API_KEY],
    // A photograph is the payload, so the default 32 MiB is not the binding
    // constraint; the request is capped in gate.js long before this.
    memory: '512MiB',
    timeoutSeconds: 90,
    // No browser calls this. The Android app posts from native code, and a
    // permissive CORS policy would only invite one.
    cors: false,
    maxInstances: 20
  },
  async (req, res) => {
    if (req.method !== 'POST') return fail(res, 405, 'method', 'POST only.');

    if (APPCHECK_ENFORCE) {
      const token = req.header('X-Firebase-AppCheck');
      if (!token) return fail(res, 401, 'no_app_check', 'This request did not come from Bitey.');
      try {
        await getAppCheck().verifyToken(token);
      } catch {
        return fail(res, 401, 'bad_app_check', 'This request did not come from Bitey.');
      }
    }

    const request = validateAnalyseRequest(req.body);
    if (!request.ok) {
      return fail(res, request.error === 'no_image' || request.error === 'image_too_large' ? 400 : 401,
        request.error, ANALYSE_ERRORS[request.error]);
    }

    // The token identifies the subscription for the rest of this request and
    // is never written down: the counter keys off a hash of it instead.
    const entitlementId = entitlementIdFor(request.purchaseToken);

    let subscription;
    try {
      subscription = await readSubscription(PLAY_PACKAGE, request.purchaseToken);
    } catch (err) {
      if (err instanceof PlayError) {
        logger.error('play verification failed', { code: err.code, detail: err.detail, entitlementId });
        return fail(res, err.status, err.code, err.message);
      }
      throw err;
    }

    if (!subscription || subscription.productId !== AI_SUBSCRIPTION_ID || !isEntitled(subscription)) {
      // Deliberately the same answer for "never bought", "expired" and "a
      // token for something else": the client shows the plans either way, and
      // distinguishing them here only helps somebody probing tokens.
      return fail(res, 403, 'not_entitled', ANALYSE_ERRORS.not_entitled);
    }

    const quota = await claim(entitlementId);
    if (!quota.allowed) {
      return fail(res, 429, 'daily_limit', ANALYSE_ERRORS.daily_limit,
        { used: quota.used, limit: quota.limit, remaining: 0 });
    }

    let raw, usage, model;
    try {
      ({ raw, usage, model } = await analysePhoto({
        apiKey: GEMINI_API_KEY.value(),
        imageBase64: request.image,
        mimeType: request.mimeType,
        correction: request.correction,
        locale: request.locale
      }));
    } catch (err) {
      if (err instanceof AnalysisError) {
        // Nothing was spent if the request never got as far as the model, so
        // the reading goes back into today's allowance.
        if (err.status === 503 || err.status === 429) await refund(entitlementId);
        logger.error('analysis failed', { code: err.code, entitlementId });
        return fail(res, err.status, err.code, err.message);
      }
      await refund(entitlementId);
      throw err;
    }

    const parsed = parseResponse(raw);
    if (!parsed.ok) {
      // "Not food" and "found nothing" are different failures with different
      // remedies, so the client is told which one happened. Both cost a
      // reading: the model was asked and answered.
      return res.status(422).json({
        error: parsed.reason, note: parsed.note, usage, model, remaining: quota.remaining
      });
    }

    const estimate = fromModelResponse({ items: parsed.items, note: parsed.note });
    if (!estimate.items.length) {
      return res.status(422).json({
        error: 'nothing_found', note: parsed.note, usage, model, remaining: quota.remaining
      });
    }

    // Token counts are logged, the photograph and the estimate are not. What
    // somebody ate is theirs; what the feature costs to run is ours to watch.
    logger.info('analysed', {
      entitlementId,
      promptTokens: usage.promptTokens,
      outputTokens: usage.outputTokens,
      remaining: quota.remaining
    });

    res.json({
      estimate,
      totals: totalsOf(estimate),
      ranges: rangesOf(estimate),
      usage,
      model,
      remaining: quota.remaining
    });
  }
);
