#!/usr/bin/env node
// Bitey AI usage, day by day, from the analyse function's own logs.
//
//   node scripts/ai-usage-report.mjs [days=14]
//
// Needs the Google Cloud CLI signed in with access to plate-cc703 (on the
// Lenovo: ~/google-cloud-sdk/bin/gcloud, picked up automatically). It only
// reads logs. Days are UTC, the same days the 20-a-day limit counts in.
//
// What a day shows:
//   readings     answers that reached the model ("analysed" + "leftovers read")
//   test         of those, readings on a licence tester's purchase (logged
//                from 12 October 2026; earlier days show 0)
//   subscribers  distinct subscriptions behind them (hashed ids, never tokens)
//   tokens       Gemini input / output tokens, as the function logged them
//   model s      median seconds spent waiting on Gemini
//   cold         readings that started a new server instance first
//   at limit     readings that used a subscriber's 20th of the day
//   refused      requests turned away, by HTTP status:
//                400 bad request, 401 App Check, 403 no subscription,
//                422 no food in the photo, 429 daily limit,
//                5xx server or Gemini failure
import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';

const PROJECT = process.env.BITEY_PROJECT || 'plate-cc703';
const days = Math.max(1, Math.min(30, Number(process.argv[2]) || 14));
const localGcloud = join(homedir(), 'google-cloud-sdk', 'bin', 'gcloud');
const gcloud = process.env.GCLOUD || (existsSync(localGcloud) ? localGcloud : 'gcloud');

function read(filter) {
  const out = execFileSync(gcloud, [
    'logging', 'read', `resource.labels.service_name="analyse" AND ${filter}`,
    '--project', PROJECT, `--freshness=${days}d`, '--format=json'
  ], { encoding: 'utf8', maxBuffer: 512 * 1024 * 1024 });
  return JSON.parse(out || '[]');
}

const answers = read('(jsonPayload.message="analysed" OR jsonPayload.message="leftovers read")');
const requests = read('httpRequest.status>=200');

const byDay = new Map();
const day = (entry) => (entry.timestamp || entry.receiveTimestamp).slice(0, 10);
const bucket = (d) => {
  if (!byDay.has(d)) {
    byDay.set(d, { readings: 0, leftovers: 0, test: 0, subs: new Set(), inTok: 0, outTok: 0, model: [],
      cold: 0, atLimit: 0, status: {} });
  }
  return byDay.get(d);
};

for (const e of answers) {
  const p = e.jsonPayload || {};
  const b = bucket(day(e));
  b.readings++;
  if (p.message === 'leftovers read') b.leftovers++;
  if (p.test === true) b.test++;
  if (p.entitlementId) b.subs.add(p.entitlementId);
  b.inTok += Number(p.promptTokens) || 0;
  b.outTok += Number(p.outputTokens) || 0;
  if (Number.isFinite(p.ms?.model)) b.model.push(p.ms.model);
  if (p.ms?.cold) b.cold++;
  if (p.remaining === 0) b.atLimit++;
}
for (const e of requests) {
  const s = Number(e.httpRequest?.status);
  if (!s || s < 400) continue;
  const key = s >= 500 ? '5xx' : String(s);
  const b = bucket(day(e));
  b.status[key] = (b.status[key] || 0) + 1;
}

const median = (xs) => {
  if (!xs.length) return null;
  const s = [...xs].sort((a, b) => a - b);
  return s[Math.floor(s.length / 2)];
};
const n = (x) => x.toLocaleString('en-US');
const pad = (s, w, right = false) => (right ? String(s).padStart(w) : String(s).padEnd(w));

const header = ['day (UTC)', 'readings', 'test', 'subs', 'in tokens', 'out tokens', 'model s', 'cold', 'at limit',
  '400', '401', '403', '422', '429', '5xx'];
const widths = [10, 8, 4, 5, 11, 10, 7, 5, 8, 4, 4, 4, 4, 4, 4];
console.log(`Bitey AI usage, project ${PROJECT}, last ${days} days\n`);
console.log(header.map((h, i) => pad(h, widths[i], i > 0)).join('  '));
console.log(widths.map((w) => '-'.repeat(w)).join('  '));

const totals = { readings: 0, test: 0, subs: new Set(), inTok: 0, outTok: 0, status: {} };
for (const d of [...byDay.keys()].sort()) {
  const b = byDay.get(d);
  const m = median(b.model);
  const row = [d, b.readings + (b.leftovers ? ` (${b.leftovers} lo)` : ''), b.test, b.subs.size, n(b.inTok), n(b.outTok),
    m === null ? '-' : (m / 1000).toFixed(1), b.cold, b.atLimit,
    b.status['400'] || 0, b.status['401'] || 0, b.status['403'] || 0, b.status['422'] || 0,
    b.status['429'] || 0, b.status['5xx'] || 0];
  console.log(row.map((v, i) => pad(v, widths[i], i > 0)).join('  '));
  totals.readings += b.readings;
  totals.test += b.test;
  b.subs.forEach((s) => totals.subs.add(s));
  totals.inTok += b.inTok;
  totals.outTok += b.outTok;
  for (const [k, v] of Object.entries(b.status)) totals.status[k] = (totals.status[k] || 0) + v;
}

console.log(`\nTotal: ${n(totals.readings)} readings (${n(totals.test)} on test purchases) ` +
  `by ${totals.subs.size} subscriptions, ` +
  `${n(totals.inTok)} input and ${n(totals.outTok)} output tokens.`);
const refused = Object.entries(totals.status).map(([k, v]) => `${k}: ${v}`).join(', ');
console.log(`Refused: ${refused || 'none'}.`);
// Prices change and differ by tier; set them to turn tokens into money.
const pin = Number(process.env.GEMINI_USD_PER_M_INPUT);
const pout = Number(process.env.GEMINI_USD_PER_M_OUTPUT);
if (pin > 0 && pout > 0) {
  const usd = totals.inTok / 1e6 * pin + totals.outTok / 1e6 * pout;
  console.log(`Gemini cost at $${pin}/M input and $${pout}/M output: about $${usd.toFixed(2)}.`);
}
