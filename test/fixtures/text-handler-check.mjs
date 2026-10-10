// Exercise the deployed handler with provider boundaries stubbed. No live
// subscriptions, credentials, network requests, or meal records are used.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import * as gate from '../../core/ai/gate.js';
import * as prompt from '../../core/analysis/prompt.js';
import * as estimate from '../../core/analysis/estimate.js';
import { AnalysisError } from '../../functions/gemini.js';

const context = vm.createContext({ process: { env: {} }, Date });
let calls, subscription, allowed, modelError, appCheckValid;
const reset = () => {
  calls = [];
  subscription = { productId: 'synthetic-ai-product', state: 'SUBSCRIPTION_STATE_ACTIVE', expiryTimeMillis: Date.now() + 60000 };
  allowed = true; modelError = null; appCheckValid = true;
};
const model = async input => {
  calls.push(['model', input]);
  if (modelError) throw modelError;
  return { raw: { is_food: true, items: [{ name: 'Eggs', grams: 100, calories: 146, protein_g: 12.6, fat_g: 10.6, carbs_g: 0.8, fiber_g: 0 }], note: 'Assumed two large eggs.' }, usage: {}, model: 'synthetic-model' };
};
const deps = {
  'firebase-functions/v2/https': { onRequest: (_options, handler) => handler },
  'firebase-functions/params': { defineSecret: () => ({ value: () => 'synthetic-api-key' }) },
  'firebase-admin/app': { initializeApp() {} },
  'firebase-admin/app-check': { getAppCheck: () => ({ verifyToken: async () => { if (!appCheckValid) throw Error('invalid'); } }) },
  'firebase-functions/logger': { default: { info() {}, error() {} } },
  './core/ai/gate.js': gate,
  './core/analysis/prompt.js': prompt,
  './core/analysis/estimate.js': estimate,
  './play.js': { readSubscription: async () => { calls.push(['play']); return subscription; }, PlayError: class extends Error {}, AI_SUBSCRIPTION_ID: 'synthetic-ai-product' },
  './quota.js': { entitlementIdFor: () => 'synthetic-hash', claim: async () => { calls.push(['quota']); return { allowed, remaining: allowed ? 19 : 0, used: allowed ? 1 : 20, limit: 20 }; }, refund: async () => { calls.push(['refund']); } },
  './gemini.js': { analysePhoto: model, analyseText: model, readLeftovers: model, AnalysisError }
};
const module = new vm.SourceTextModule(fs.readFileSync(new URL('../../functions/index.js', import.meta.url), 'utf8'), { context });
await module.link(specifier => {
  const exports = deps[specifier];
  if (!exports) throw Error(`Unmocked boundary: ${specifier}`);
  return new vm.SyntheticModule(Object.keys(exports), function () {
    for (const [key, value] of Object.entries(exports)) this.setExport(key, value);
  }, { context });
});
await module.evaluate();
const invoke = async (body = {}, appCheck = 'synthetic-attestation') => {
  const response = { statusCode: 200, body: null, status(code) { this.statusCode = code; return this; }, json(value) { this.body = value; return this; } };
  await module.namespace.analyse({ method: 'POST', header: () => appCheck, body: { mode: 'text', description: 'two eggs', purchaseToken: 'synthetic-purchase-token', ...body } }, response);
  return response;
};

reset(); assert.equal((await invoke({}, '')).statusCode, 401); assert.deepEqual(calls, []);
reset(); appCheckValid=false; assert.equal((await invoke()).statusCode, 401); assert.deepEqual(calls, []);
reset(); assert.equal((await invoke({ purchaseToken: '' })).statusCode, 401); assert.deepEqual(calls, []);
for (const state of ['SUBSCRIPTION_STATE_EXPIRED', 'SUBSCRIPTION_STATE_PENDING', 'SUBSCRIPTION_STATE_ON_HOLD', 'SUBSCRIPTION_STATE_PAUSED']) {
  reset(); subscription.state=state;
  assert.equal((await invoke()).statusCode, 403); assert.deepEqual(calls, [['play']]);
}
reset(); subscription.expiryTimeMillis=Date.now()-1000;
assert.equal((await invoke()).statusCode,403); assert.deepEqual(calls,[['play']]);
reset(); subscription.productId='synthetic-other-product';
assert.equal((await invoke()).statusCode,403); assert.deepEqual(calls,[['play']]);
reset(); allowed=false;
assert.equal((await invoke()).statusCode,429); assert.deepEqual(calls,[['play'],['quota']]);
reset(); const success=await invoke({ image:'ignored', diet:'vegan', profile:{weightKg:80} });
assert.equal(success.statusCode,200);
assert.deepEqual(calls.map(c=>c[0]),['play','quota','model']);
assert.deepEqual(Object.keys(calls[2][1]).sort(),['apiKey','description','locale']);
assert.equal(success.body.estimate.items[0].source,'text');
assert.equal(success.body.ranges.calories.low,null);
assert.equal(success.body.remaining,19);
reset(); modelError=new AnalysisError('upstream_unreachable','Synthetic outage',503);
assert.equal((await invoke()).statusCode,503);
assert.deepEqual(calls.map(c=>c[0]),['play','quota','model','refund']);
reset(); const photo=await invoke({mode:'analyse',image:'x'.repeat(200)});
assert.equal(photo.statusCode,200); assert.equal(photo.body.estimate.items[0].source,'photo');
console.log('Cloud paywall, App Check, shared quota, refund, and photo regression checks passed.');
