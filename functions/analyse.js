// The analysis function itself, built by index.js (production) and beta.js.
//
// The Android app has no account and no server of its own, so this is the only
// place where "has this person paid?" can be answered truthfully. The client's
// own answer is useful for drawing the interface and is worth nothing here:
// every call re-verifies the purchase with Google Play before a photograph
// reaches the model.
//
// What crosses this boundary is one photograph and, if the person typed one, a
// correction in their own words -- plus their diet, only if they turned that on
// in Settings. Never the diary, never the rest of the profile, never a backup. What is stored is a number per subscription per day.

import { onRequest } from 'firebase-functions/v2/https';
import { defineSecret } from 'firebase-functions/params';
import { initializeApp } from 'firebase-admin/app';
import { getAppCheck } from 'firebase-admin/app-check';
import { getAuth } from 'firebase-admin/auth';
import * as logger from 'firebase-functions/logger';

import { validateAnalyseRequest, isEntitled, ANALYSE_ERRORS, resetsAt } from './core/ai/gate.js';
import { parseResponse, parseLeftovers } from './core/analysis/prompt.js';
import { fromModelResponse, totalsOf, rangesOf } from './core/analysis/estimate.js';
import { readSubscription, PlayError, AI_SUBSCRIPTION_ID } from './play.js';
import {
  COLLECTIONS, entitlementIdFor, freeIdFor, claim, refund, peek, freeConfig, claimFree, peekFree, refundFree
} from './quota.js';
import { analysePhoto, analyseText, readLeftovers, AnalysisError } from './gemini.js';

initializeApp();

const GEMINI_API_KEY = defineSecret('GEMINI_API_KEY');

// Plain environment values rather than Firebase params: neither is a secret,
// and a param -- even one with a default -- must be confirmed interactively at
// every deploy, which a scripted deploy cannot do. Firebase still loads
// functions/.env.<project> into process.env, so either can be overridden
// there without a code change.
const PLAY_PACKAGE = (process.env.PLAY_PACKAGE || 'com.zandaulion.bitey').trim();
// On since 26 September 2026, once the Play-installed 1.0.6 was seen arriving
// "valid". It is the default in code rather than a value in functions/.env,
// which is git-ignored: a deploy from a checkout without that file must not
// quietly switch protection off. APPCHECK_ENFORCE=false is the emergency
// switch, for an App Check outage that is refusing genuine phones.
const APPCHECK_ENFORCE = process.env.APPCHECK_ENFORCE !== 'false';

// False until this instance has served its first request, so the timings
// say which readings paid for starting an instance.
let warm = false;

const fail = (res, status, code, message, extra = {}) =>
  res.status(status).json({ error: code, message, ...extra });

/**
 * Builds the analysis function. Production (index.js) takes every default.
 * The beta build (beta.js) passes its own subscription check and its own
 * collections, so test traffic never touches production's counters or
 * settings, and production never loads the beta's tester rule.
 */
export function buildAnalyse({
  verifySubscription = readSubscription,
  collections = COLLECTIONS,
  maxInstances = 5,
  // Beta only: an extra wait after the model has answered, so a reading
  // takes about as long as a slow one does in real use (beta.js).
  delayMs = 0
} = {}) {
  return onRequest(
    {
      region: 'europe-west1',
      // A service account of its own rather than the project's default compute
      // account, which carries Editor on the whole project. This one holds only
      // what the function uses: Play read access for Bitey (granted in Play
      // Console), its one secret (granted by the deploy), Firestore and logs.
      // It is also the identity Play Console is told about, so the grant there
      // names exactly one piece of software.
      serviceAccount: 'bitey-play@plate-cc703.iam.gserviceaccount.com',
      secrets: [GEMINI_API_KEY],
      // A photograph is the payload, so the default 32 MiB is not the binding
      // constraint; the request is capped in gate.js long before this.
      memory: '512MiB',
      timeoutSeconds: 90,
      // No browser calls this. The Android app posts from native code, and a
      // permissive CORS policy would only invite one.
      cors: false,
      // The ceiling on what a flood of requests can cost, not a performance
      // setting. Each instance serves 80 requests at once, and a reading is
      // mostly spent waiting on Gemini, so five instances carry 400 readings in
      // flight -- far beyond current use. Five running flat out around the
      // clock is roughly $10 a day; twenty was four times that. Raise it when
      // real traffic needs it, not before.
      maxInstances
    },
    async (req, res) => {
      if (req.method !== 'POST') return fail(res, 405, 'method', 'POST only.');

      // Milliseconds per step, logged with the outcome: a slow reading should
      // say where the time went. Durations only -- nothing about the photo.
      const ms = { cold: !warm };
      warm = true;
      let last = Date.now();
      const lap = (step) => { const now = Date.now(); ms[step] = now - last; last = now; };

      // Always checked and logged, so a rise in "absent" or "invalid" shows up
      // in the logs; refused unless APPCHECK_ENFORCE has been set to false.
      const appCheckToken = req.header('X-Firebase-AppCheck');
      let appCheck = 'absent';
      if (appCheckToken) {
        try {
          await getAppCheck().verifyToken(appCheckToken);
          appCheck = 'valid';
        } catch {
          appCheck = 'invalid';
        }
      }
      lap('appCheck');
      logger.info('app check', { appCheck, enforced: APPCHECK_ENFORCE });
      if (APPCHECK_ENFORCE && appCheck !== 'valid') {
        return fail(res, 401, appCheck === 'absent' ? 'no_app_check' : 'bad_app_check',
          'This request did not come from Bitey.');
      }

      const request = validateAnalyseRequest(req.body);
      if (!request.ok) {
        const shape = ['no_image', 'image_too_large', 'no_original', 'no_items',
          'no_description', 'description_too_long', 'bad_mode'].includes(request.error);
        return fail(res, shape ? 400 : 401, request.error, ANALYSE_ERRORS[request.error]);
      }

      // Who is asking decides which allowance is spent. A live subscription
      // is Bitey AI (tier "paid"); anyone else with the app's anonymous
      // sign-in is the free tier. Someone whose subscription has lapsed sends
      // both, and falls back to the free tier rather than being refused.
      let tier = null;
      let entitlementId = null;
      let subscription = null;

      if (request.purchaseToken) {
        // The token identifies the subscription for the rest of this request
        // and is never written down: the counter keys off a hash of it instead.
        entitlementId = entitlementIdFor(request.purchaseToken);
        try {
          subscription = await verifySubscription(PLAY_PACKAGE, request.purchaseToken);
          lap('play');
        } catch (err) {
          if (err instanceof PlayError) {
            logger.error('play verification failed', { code: err.code, detail: err.detail, entitlementId });
            return fail(res, err.status, err.code, err.message);
          }
          throw err;
        }

        if (subscription && subscription.productId === AI_SUBSCRIPTION_ID && isEntitled(subscription)) {
          tier = 'paid';
        } else {
          // What Play said, so a refusal can be explained: the state, product,
          // plan and expiry. The token is not logged; the hashed id is the same
          // one the daily counter uses.
          logger.warn('not entitled', {
            entitlementId,
            test: subscription?.test ?? false,
            state: subscription?.state ?? null,
            productId: subscription?.productId ?? null,
            basePlanId: subscription?.basePlanId ?? null,
            expiry: subscription?.expiryTimeMillis ? new Date(subscription.expiryTimeMillis).toISOString() : null,
            freeFallback: Boolean(request.idToken)
          });
          subscription = null;
        }
      }

      if (!tier && request.idToken) {
        let uid;
        try {
          ({ uid } = await getAuth().verifyIdToken(request.idToken));
        } catch {
          lap('auth');
          return fail(res, 401, 'bad_id_token', ANALYSE_ERRORS.bad_id_token);
        }
        lap('auth');
        tier = 'free';
        entitlementId = freeIdFor(uid);
      }

      if (!tier) {
        // Deliberately the same answer for "never bought", "expired" and "a
        // token for something else": the client shows the plans either way,
        // and distinguishing them here only helps somebody probing tokens.
        return fail(res, 403, 'not_entitled', ANALYSE_ERRORS.not_entitled);
      }

      // Only asking what is left: answered from the counters, nothing claimed,
      // no model called.
      if (request.mode === 'allowance') {
        if (tier === 'paid') {
          const left = await peek(entitlementId, { collection: collections.paid });
          lap('quota');
          return res.json({ tier, remaining: left.remaining, limit: left.limit, resetsAt: resetsAt() });
        }
        const config = await freeConfig({ path: collections.freeConfig });
        const left = await peekFree(entitlementId, { config, collections });
        lap('quota');
        return res.json({
          tier, remaining: left.remaining, limit: left.limit,
          correctionsRemaining: left.correctionsRemaining, correctionsLimit: left.correctionsLimit,
          resetsAt: resetsAt()
        });
      }

      const correction = request.mode === 'analyse' && Boolean(request.correction);
      let quota;
      if (tier === 'paid') {
        quota = await claim(entitlementId, { requestId: request.requestId, collection: collections.paid });
        lap('quota');
        if (!quota.allowed) {
          return fail(res, 429, 'daily_limit', ANALYSE_ERRORS.daily_limit,
            { tier, used: quota.used, limit: quota.limit, remaining: 0, resetsAt: resetsAt() });
        }
      } else {
        const config = await freeConfig({ path: collections.freeConfig });
        quota = await claimFree(entitlementId, {
          correction, requestId: request.requestId, config, collections
        });
        lap('quota');
        // Once a day each: the pool document remembers it fired. A log-based
        // alert in Cloud Monitoring turns this line into an email.
        for (const level of quota.alerts) {
          logger.warn('free pool alert', { level, count: quota.poolUsed, poolLimit: config.poolLimit });
        }
        if (!quota.allowed) {
          logger.info('free refused', { reason: quota.reason, entitlementId, correction });
          return fail(res, 429, quota.reason, ANALYSE_ERRORS[quota.reason], {
            tier, remaining: quota.remaining, correctionsRemaining: quota.correctionsRemaining,
            correctionsLimit: quota.correctionsLimit, limit: quota.limit, resetsAt: resetsAt()
          });
        }
      }

      // What is left today, sent with every answer so the app can show it.
      const allowance = {
        tier,
        remaining: quota.remaining,
        ...(tier === 'free'
          ? { correctionsRemaining: quota.correctionsRemaining, correctionsLimit: quota.correctionsLimit, limit: quota.limit }
          : {}),
        resetsAt: resetsAt()
      };
      // A repeated request id was not charged, so there is nothing to give back.
      const giveBack = () => quota.repeat ? undefined
        : tier === 'paid'
          ? refund(entitlementId, { requestId: request.requestId, collection: collections.paid })
          : refundFree(entitlementId, { counter: quota.counter, requestId: request.requestId, collections });

      // A leftovers reading sends two photographs but is one reading: it is one
      // question about one meal, as on the PWA's server.
      const leftovers = request.mode === 'leftovers';
      let raw, usage, model;
      try {
        ({ raw, usage, model } = leftovers
          ? await readLeftovers({
            apiKey: GEMINI_API_KEY.value(),
            beforeBase64: request.original,
            beforeMimeType: request.originalMimeType,
            afterBase64: request.image,
            afterMimeType: request.mimeType,
            items: request.items,
            locale: request.locale
          })
          : request.mode === 'text'
          ? await analyseText({ apiKey: GEMINI_API_KEY.value(), description: request.description, locale: request.locale })
          : await analysePhoto({
            apiKey: GEMINI_API_KEY.value(),
            imageBase64: request.image,
            mimeType: request.mimeType,
            correction: request.correction,
            locale: request.locale,
            diet: request.diet
          }));
        if (delayMs > 0) await new Promise((resolve) => setTimeout(resolve, delayMs));
        lap('model');
      } catch (err) {
        lap('model');
        if (err instanceof AnalysisError) {
          // Nothing was spent if the request never got as far as the model, so
          // the reading goes back into today's allowance.
          if (err.status === 503 || err.status === 429) await giveBack();
          logger.error('analysis failed', { code: err.code, entitlementId, ms });
          return fail(res, err.status, err.code, err.message);
        }
        await giveBack();
        throw err;
      }

      // A reading the model turned down, logged with the reason only: which
      // refusal it was is the one thing the request log cannot show, and the
      // model's note describes the meal, which stays private like the photo.
      const refused = (reason) => logger.warn('refused by model', {
        reason,
        mode: leftovers ? 'leftovers' : request.mode === 'text' ? 'text' : 'photo',
        entitlementId,
        tier,
          test: subscription?.test ?? false,
        ms,
        promptTokens: usage.promptTokens,
        outputTokens: usage.outputTokens,
        ...allowance
      });

      if (leftovers) {
        const read = parseLeftovers(raw, request.items);
        if (!read.ok) {
          refused(read.reason);
          // "That is a different meal" is the model doing its job, and it cost a
          // reading like any other answer. Rewriting an entry on the strength of
          // a photograph of something else would be the actual harm.
          return res.status(422).json({
            error: read.reason,
            message: read.reason === 'different_meal'
              ? `That does not look like the same meal. ${read.note}`.trim()
              : 'The leftovers could not be read. Set how much you ate by hand instead.',
            note: read.note, usage, model, ...allowance
          });
        }
        logger.info('leftovers read', {
          entitlementId,
          tier,
          test: subscription?.test ?? false,
          ms,
          promptTokens: usage.promptTokens,
          outputTokens: usage.outputTokens,
          ...allowance
        });
        // Fractions only. The client applies them to the estimate in front of
        // the person, and nothing changes until they save.
        return res.json({ eaten: read.eaten, note: read.note, usage, model, ...allowance });
      }

      const parsed = parseResponse(raw);
      if (!parsed.ok) {
        // "Not food" and "found nothing" are different failures with different
        // remedies, so the client is told which one happened. Both cost a
        // reading: the model was asked and answered.
        refused(parsed.reason);
        return res.status(422).json({
          error: parsed.reason, note: parsed.note, usage, model, ...allowance
        });
      }

      const estimate = fromModelResponse({ items: parsed.items, note: parsed.note },
        request.mode === 'text' ? 'text' : 'photo');
      if (!estimate.items.length) {
        refused('nothing_found');
        return res.status(422).json({
          error: 'nothing_found', note: parsed.note, usage, model, ...allowance
        });
      }

      // Token counts are logged, the photograph and the estimate are not. What
      // somebody ate is theirs; what the feature costs to run is ours to watch.
      logger.info('analysed', {
        entitlementId,
        tier,
          test: subscription?.test ?? false,
        ms,
        promptTokens: usage.promptTokens,
        outputTokens: usage.outputTokens,
        ...allowance
      });

      res.json({
        estimate,
        totals: totalsOf(estimate),
        ranges: rangesOf(estimate),
        usage,
        model,
        ...allowance
      });
    }
  );
}
