// Bitey Beta's analysis function, deployed apart from production as the
// `bitey-ai-beta` codebase (scripts/deploy-beta-function.sh) and called only
// by the beta build type of the Android app.
//
// It differs from production in three ways, and in nothing else:
// - its usage counters and free-tier settings live in their own collections
//   (beta_*), so test readings never mix with real ones and its free tier can
//   be set differently -- a small pool, say, to reach "used up" quickly;
// - one instance at most;
// - a tester token listed in BETA_TESTER_TOKENS counts as a live
//   subscription, so the paid path can be tested from an app Play will not
//   sell to. Every other token is still verified with Google Play.
//
// Production's entry point (index.js) never imports this file.

import { buildAnalyse } from './analyse.js';
import { readSubscription, AI_SUBSCRIPTION_ID } from './play.js';

/** Comma-separated, set only in the beta deploy's own .env file. */
const testerTokens = new Set((process.env.BETA_TESTER_TOKENS || '')
  .split(',').map((token) => token.trim()).filter(Boolean));

export async function testerOrPlay(packageName, purchaseToken) {
  if (testerTokens.has(purchaseToken)) {
    return {
      productId: AI_SUBSCRIPTION_ID,
      state: 'SUBSCRIPTION_STATE_ACTIVE',
      basePlanId: 'tester',
      // An hour ahead of each request: always live, and never mistaken for a
      // real subscription's expiry in the logs.
      expiryTimeMillis: String(Date.now() + 3600000),
      test: true
    };
  }
  return readSubscription(packageName, purchaseToken);
}

export const analyseBeta = buildAnalyse({
  verifySubscription: testerOrPlay,
  collections: {
    paid: 'beta_ai_usage',
    free: 'beta_ai_usage_free',
    pool: 'beta_ai_usage_free_pool',
    freeConfig: 'beta_config/free_tier'
  },
  maxInstances: 1
});
