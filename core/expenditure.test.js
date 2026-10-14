import test from 'node:test';
import assert from 'node:assert/strict';
import { adaptiveExpenditure, dailyIntake, KCAL_PER_KG, REQUIREMENTS, MIN_PLAUSIBLE_KCAL } from './expenditure.js';
import { weightTrend } from './weight.js';

const DAY = 86400000;
const NOW = Date.parse('2026-08-30T12:00:00Z');
const dayKey = (offset) => new Date(NOW - offset * DAY).toISOString().slice(0, 10);

const PROFILE = { weightKg: 80, heightCm: 180, ageYears: 30, sex: 'male', activity: 'moderate' };

/**
 * Builds a person whose true expenditure is known, so the estimate can be
 * checked against the right answer rather than against itself.
 *
 * Eating `intake` against a true expenditure of `tdee` puts them in a deficit
 * of (tdee - intake) kcal/day, which the identity says must show up as
 * (deficit / KCAL_PER_KG) kg/day of weight change.
 */
function simulate({ tdee, intake, days = 28, startKg = 80, noiseKg = 0, weighEvery = 1, logEvery = 1 }) {
  const slope = (intake - tdee) / KCAL_PER_KG; // negative when in deficit
  const entries = [];
  const weights = [];

  // Deterministic pseudo-noise, so a failure is reproducible.
  let seed = 42;
  const rand = () => (seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff;

  for (let i = days; i >= 0; i--) {
    const at = new Date(NOW - i * DAY).toISOString();
    if (i % logEvery === 0) {
      entries.push({
        day: dayKey(i),
        portionSource: 'weighed',
        totals: { calories: intake, protein: 0, fat: 0, carbs: 0 }
      });
    }
    if (i % weighEvery === 0) {
      const trueKg = startKg + slope * (days - i);
      weights.push({ at, kg: trueKg + (rand() - 0.5) * 2 * noiseKg });
    }
  }
  return { entries, weights };
}

test('recovers a known expenditure from intake and weight change', () => {
  // 2,000 kcal/day against a true 2,500 is a 500 kcal deficit: about
  // 0.065 kg/day, or 0.45 kg a week.
  const { entries, weights } = simulate({ tdee: 2500, intake: 2000 });
  const out = adaptiveExpenditure({ entries, weights, profile: PROFILE, now: NOW });

  // The measurement on its own; the published figure is it blended with the
  // formula, tested separately below.
  assert.equal(out.method, 'measured');
  assert.ok(Math.abs(out.measured.kcal - 2500) < 25, `expected ~2500, got ${out.measured.kcal}`);
  assert.ok(out.measured.low < 2500 && 2500 < out.measured.high, 'the band must contain the truth');
  assert.ok(Math.abs(out.basis.slopeKgPerWeek + 0.455) < 0.02, `slope ${out.basis.slopeKgPerWeek}`);
});

test('recovers expenditure for someone gaining weight too', () => {
  const { entries, weights } = simulate({ tdee: 2200, intake: 2700 });
  const out = adaptiveExpenditure({ entries, weights, profile: PROFILE, now: NOW });
  assert.ok(Math.abs(out.measured.kcal - 2200) < 25, `expected ~2200, got ${out.measured.kcal}`);
  assert.ok(out.basis.slopeKgPerWeek > 0, 'gaining weight must show a positive slope');
});

test('weight noise widens the band without moving the estimate much', () => {
  const clean = adaptiveExpenditure({ ...simulate({ tdee: 2500, intake: 2000 }), profile: PROFILE, now: NOW });
  const noisy = adaptiveExpenditure({
    ...simulate({ tdee: 2500, intake: 2000, noiseKg: 1.2 }), profile: PROFILE, now: NOW
  });

  const width = (o) => o.measured.high - o.measured.low;
  assert.ok(width(noisy) > width(clean) * 1.5,
    `a noisy weigher must get a visibly wider band (${width(clean)} -> ${width(noisy)})`);
  assert.ok(Math.abs(noisy.measured.kcal - 2500) < width(noisy), 'and it must stay near the truth');
  assert.ok(noisy.measuredShare < clean.measuredShare, 'and lean on the formula more');
});

test('refuses when too few days are logged, and says what is missing', () => {
  const { entries, weights } = simulate({ tdee: 2500, intake: 2000, logEvery: 4 });
  const out = adaptiveExpenditure({ entries, weights, profile: PROFILE, now: NOW });

  assert.equal(out.method, 'formula');
  const gaps = out.missing.map((m) => m.what);
  assert.ok(gaps.includes('logged_days') || gaps.includes('coverage'));
  assert.ok(out.progress.neededDays >= REQUIREMENTS.minLoggedDays);
});

test('refuses on poor coverage even when the day count is met', () => {
  // 15 logged days is over the count, but spread across a 60-day history the
  // window is only half covered and the unlogged days are unaccounted for.
  const { weights } = simulate({ tdee: 2500, intake: 2000 });
  const entries = [];
  for (let i = 0; i < 28; i += 2) {
    entries.push({ day: dayKey(i), portionSource: 'weighed', totals: { calories: 2000 } });
  }
  const out = adaptiveExpenditure({ entries, weights, profile: PROFILE, now: NOW });
  assert.equal(out.method, 'formula');
  assert.ok(out.missing.some((m) => m.what === 'coverage'));
});

test('refuses without enough weighings', () => {
  const { entries } = simulate({ tdee: 2500, intake: 2000 });
  const out = adaptiveExpenditure({ entries, weights: [], profile: PROFILE, now: NOW });
  assert.equal(out.method, 'formula');
  assert.ok(out.missing.some((m) => m.what === 'weighings'));
});

test('a barely-logged day is not counted as a day of fasting', () => {
  const { weights } = simulate({ tdee: 2500, intake: 2000 });
  const entries = [];
  for (let i = 0; i < 28; i++) {
    // Every fourth day only a coffee got logged.
    const calories = i % 4 === 0 ? 90 : 2000;
    entries.push({ day: dayKey(i), portionSource: 'weighed', totals: { calories } });
  }
  const out = adaptiveExpenditure({ entries, weights, profile: PROFILE, now: NOW });
  assert.ok(MIN_PLAUSIBLE_KCAL > 90);
  // Those days are dropped, not averaged in -- averaging them would drag mean
  // intake down and inflate expenditure by hundreds of kcal.
  assert.ok(out.basis === undefined || out.basis.meanIntake >= 1900,
    'partly-logged days must not drag the mean down');
});

test('falls back to the formula, labelled, and still reports progress', () => {
  const out = adaptiveExpenditure({ entries: [], weights: [], profile: PROFILE, now: NOW });
  assert.equal(out.method, 'formula');
  assert.equal(out.available, true);
  assert.equal(out.kcal, Math.round(1780 * 1.55), 'the Mifflin-St Jeor answer');
  assert.equal(out.progress.loggedDays, 0);
});

test('with no profile and no data it reports unavailable rather than guessing', () => {
  const out = adaptiveExpenditure({ entries: [], weights: [], profile: null, now: NOW });
  assert.equal(out.method, 'formula');
  assert.equal(out.available, false);
  assert.equal(out.kcal, undefined);
});

test('the measured answer carries the formula alongside it', () => {
  const { entries, weights } = simulate({ tdee: 2500, intake: 2000 });
  const out = adaptiveExpenditure({ entries, weights, profile: PROFILE, now: NOW });
  // A large gap between the two usually means intake is being under-logged,
  // which is worth being able to see.
  assert.ok(out.formula.kcal > 0);
  assert.notEqual(out.kcal, out.formula.kcal);
});

test('dailyIntake sums a day and blends its uncertainty by calories', () => {
  const rows = dailyIntake([
    { day: '2026-08-20', portionSource: 'weighed', totals: { calories: 800 } },
    { day: '2026-08-20', portionSource: 'model', totals: { calories: 200 } },
    { day: '2026-08-21', portionSource: 'model', totals: { calories: 500 } }
  ]);
  assert.equal(rows.length, 2);
  assert.equal(rows[0].calories, 1000);
  // Weighted towards the weighed 800 kcal, so between the two bands and nearer
  // the tighter one.
  assert.ok(rows[0].band > 0.16 && rows[0].band < 0.30);
  assert.ok(rows[0].band < rows[1].band);
});

test('history older than the window is ignored', () => {
  const { entries, weights } = simulate({ tdee: 2500, intake: 2000 });
  const stale = [{ day: '2020-01-01', portionSource: 'weighed', totals: { calories: 9000 } }];
  const out = adaptiveExpenditure({
    entries: [...stale, ...entries], weights, profile: PROFILE, now: NOW
  });
  assert.ok(Math.abs(out.measured.kcal - 2500) < 25, 'an old outlier must not reach the mean');
});

test('a new user gets a measurement once two weeks are in, not after the whole window', () => {
  // Fifteen days of history against a 42-day window: coverage is taken over
  // the days since logging began, so this is fully covered.
  const { entries, weights } = simulate({ tdee: 2500, intake: 2000, days: 15 });
  const out = adaptiveExpenditure({ entries, weights, profile: PROFILE, now: NOW });
  assert.ok(REQUIREMENTS.windowDays > 15);
  assert.equal(out.method, 'measured', JSON.stringify(out.missing));
  assert.equal(out.basis.windowDays, 15);
  assert.equal(out.basis.coveragePct, 100);
});

test('today is left out, because it is still being eaten', () => {
  const { entries, weights } = simulate({ tdee: 2500, intake: 2000 });
  const base = adaptiveExpenditure({ entries, weights, profile: PROFILE, now: NOW });
  // Breakfast so far. Counted as a day, it would drag the mean intake down.
  const morning = entries.filter((e) => e.day !== dayKey(0))
    .concat({ day: dayKey(0), portionSource: 'weighed', totals: { calories: 450 } });
  const out = adaptiveExpenditure({ entries: morning, weights, profile: PROFILE, now: NOW });
  assert.equal(out.kcal, base.kcal);
  assert.equal(out.basis.meanIntake, 2000);
});

test('the window holds at most its length in days', () => {
  const { entries, weights } = simulate({ tdee: 2500, intake: 2000, days: 80 });
  const out = adaptiveExpenditure({ entries, weights, profile: PROFILE, now: NOW });
  assert.equal(out.basis.windowDays, REQUIREMENTS.windowDays);
  assert.equal(out.basis.loggedDays, REQUIREMENTS.windowDays);
  assert.equal(out.basis.coveragePct, 100);
});

test('the formula is weighed in by how sure each side is', () => {
  // A noisy weigher, so the measurement is uncertain enough to share.
  const { entries, weights } = simulate({ tdee: 2300, intake: 2000, noiseKg: 1.2 });
  const out = adaptiveExpenditure({ entries, weights, profile: PROFILE, now: NOW });
  const lo = Math.min(out.measured.kcal, out.formula.kcal);
  const hi = Math.max(out.measured.kcal, out.formula.kcal);
  assert.ok(lo < out.kcal && out.kcal < hi, `${out.kcal} should sit between ${lo} and ${hi}`);
  assert.ok(out.measuredShare > 0 && out.measuredShare < 1);
  // Two readings together are surer than either alone.
  assert.ok(out.high - out.low < out.measured.high - out.measured.low);
  assert.ok(out.high - out.low < out.formula.high - out.formula.low);
});

test('starting to measure narrows the band rather than widening it', () => {
  // The first day a measurement is possible, with noisy weighing: the figure
  // must not jump to a band wider than the formula it replaces.
  const { entries, weights } = simulate({ tdee: 2500, intake: 2000, days: 15, noiseKg: 1.5 });
  const formula = adaptiveExpenditure({ entries: [], weights: [], profile: PROFILE, now: NOW });
  const out = adaptiveExpenditure({ entries, weights, profile: PROFILE, now: NOW });
  assert.equal(out.method, 'measured');
  assert.ok(out.high - out.low <= formula.high - formula.low,
    `${out.high - out.low} wide against the formula's ${formula.high - formula.low}`);
});

test('more weeks of evidence move the figure towards the measurement', () => {
  const early = adaptiveExpenditure({
    ...simulate({ tdee: 2300, intake: 2000, days: 15, noiseKg: 1 }), profile: PROFILE, now: NOW
  });
  const later = adaptiveExpenditure({
    ...simulate({ tdee: 2300, intake: 2000, days: 42, noiseKg: 1 }), profile: PROFILE, now: NOW
  });
  assert.ok(later.measuredShare > early.measuredShare,
    `${early.measuredShare} -> ${later.measuredShare}`);
});

test('without a profile the measurement stands alone', () => {
  const { entries, weights } = simulate({ tdee: 2500, intake: 2000, noiseKg: 1 });
  const out = adaptiveExpenditure({ entries, weights, profile: null, now: NOW });
  assert.equal(out.method, 'measured');
  assert.equal(out.measuredShare, 1);
  assert.equal(out.kcal, out.measured.kcal);
  assert.equal(out.formula, null);
});

test('a back-filled weigh-in counts on the day it is about', () => {
  // Typed in today, but about a morning years ago. Its timestamp is recent;
  // its day is not, and the day is what the reading describes.
  const { entries, weights } = simulate({ tdee: 2500, intake: 2000 });
  const old = { day: '2020-01-01', at: new Date(NOW).toISOString(), kg: 120 };
  const out = adaptiveExpenditure({ entries, weights: [...weights.map((w) => ({ ...w, day: w.at.slice(0, 10) })), old], profile: PROFILE, now: NOW });
  assert.ok(Math.abs(out.measured.kcal - 2500) < 25, `got ${out.measured.kcal}`);
});

test('the window it reports gives the chart the same line the figure used', () => {
  // Readings from before the window pull an all-history fit another way; the
  // chart fits only from window.from, and must land on the same slope.
  const { entries, weights } = simulate({ tdee: 2500, intake: 2000, days: 60, noiseKg: 0.8 });
  const out = adaptiveExpenditure({ entries, weights, profile: PROFILE, now: NOW });
  const withDays = weights.map((w) => ({ ...w, day: w.at.slice(0, 10) }));
  const chart = weightTrend(withDays.filter((w) => w.day >= out.window.from));
  assert.equal(out.window.days, REQUIREMENTS.windowDays);
  assert.equal(Math.round(chart.slopeKgPerWeek * 1000) / 1000, out.basis.slopeKgPerWeek);
});

test('the window is reported before a measurement is possible too', () => {
  const out = adaptiveExpenditure({ entries: [], weights: [], profile: PROFILE, now: NOW });
  assert.equal(out.method, 'formula');
  assert.ok(out.window.from < dayKey(0));
});
