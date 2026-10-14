// What someone actually burns, derived from evidence rather than a formula.
//
// Mifflin-St Jeor estimates expenditure from height, weight, age and sex, then
// multiplies by an activity factor the user picks from a list. It is a
// population average with an arbitrary coefficient bolted on, and it cannot
// notice that a particular person runs cold, fidgets, or has adapted to months
// of dieting.
//
// The energy balance identity gives a better answer when there is enough data:
//
//     expenditure = intake - (change in stored energy)
//
// Stored energy shows up as body mass. So over a window, with mean daily
// intake and the trend in weight:
//
//     expenditure = mean_intake - slope_kg_per_day * KCAL_PER_KG
//
// This is a *measurement*, not a prescription. It says what someone is
// burning; it does not say what they should eat. That distinction is why the
// approach belongs in this app at all.

import { weightTrend } from './weight.js';
import { maintenanceEnergy, missingForMaintenance } from './nutrition.js';
import { ERROR_BANDS, portionSourceOf } from './analysis/estimate.js';

/**
 * Energy per kilogram of body mass change. The classic 7,700 kcal/kg figure.
 *
 * It is an approximation: fat carries roughly 9,400 kcal/kg and lean tissue far
 * less, because it is mostly water, so the true value depends on what is being
 * gained or lost. `KCAL_PER_KG_UNCERTAINTY` carries that into the band rather
 * than pretending the constant is exact.
 */
export const KCAL_PER_KG = 7700;
export const KCAL_PER_KG_UNCERTAINTY = 0.08;

/** A day below this is a partly-logged day, not a day of near-fasting. */
export const MIN_PLAUSIBLE_KCAL = 400;

export const REQUIREMENTS = {
  // The window grows to this length and then rolls. Weight noise is what
  // limits the estimate, and a trend's uncertainty falls steeply with the span
  // it is fitted over: at four weeks a few heavy mornings near either end
  // could move the answer by hundreds of kcal. Six weeks still notices a
  // change in someone's burn within a month or so.
  windowDays: 42,
  minLoggedDays: 14,
  // Unlogged days are the real hazard. Weight change reflects every day in the
  // window, but mean intake can only be taken over the days that were logged,
  // so using it assumes the missing days resembled the logged ones. Missed
  // days are rarely typical -- they are disproportionately the unusual meals --
  // so the estimate is refused rather than issued with a caveat nobody reads.
  //
  // Coverage is taken over the days since logging began, not over the whole
  // window, so someone in their third week is not asked for six weeks of
  // history before they get a measurement.
  minCoverage: 0.75,
  minWeighings: 6,
  minWeightSpanDays: 14
};

/**
 * How far the formula is trusted, as one standard deviation of its answer.
 *
 * Mifflin-St Jeor lands within about 10% of measured resting rate for most
 * people, and `maintenanceEnergy` draws its band at that width; this is the
 * same 10% read as one standard deviation. It is what lets the formula hold
 * the figure steady while the measurement is young, and step aside as the
 * measurement firms up.
 */
export const FORMULA_SD = 0.10;

const DAY_MS = 86400000;
const dayKey = (t) => new Date(t).toISOString().slice(0, 10);

/** Blended error band for a day's calories, from how its portions were set. */
function dayBand(entries) {
  const rows = entries || [];
  if (!rows.length) return ERROR_BANDS.model.calories;

  let weighted = 0;
  let total = 0;
  for (const e of rows) {
    const kcal = Number(e?.totals?.calories) || 0;
    if (kcal <= 0) continue;
    const band = ERROR_BANDS[portionSourceOf(e)]?.calories ?? ERROR_BANDS.model.calories;
    weighted += band * kcal;
    total += kcal;
  }
  return total > 0 ? weighted / total : ERROR_BANDS.model.calories;
}

/** Collapses entries into one row per day, with that day's uncertainty. */
export function dailyIntake(entries) {
  const byDay = new Map();
  for (const e of entries || []) {
    if (!e?.day) continue;
    if (!byDay.has(e.day)) byDay.set(e.day, []);
    byDay.get(e.day).push(e);
  }

  return [...byDay.entries()].map(([day, rows]) => ({
    day,
    calories: rows.reduce((a, r) => a + (Number(r?.totals?.calories) || 0), 0),
    band: dayBand(rows)
  })).sort((a, b) => (a.day < b.day ? -1 : 1));
}

/**
 * Expenditure from evidence, or an explanation of what is missing.
 *
 * Always returns an object with `method`, so callers never have to guess
 * whether they are holding a measurement or a formula.
 *
 * Today is left out. A day still being eaten is not a low-intake day, and
 * counting this morning's breakfast as a whole day dragged the mean down
 * until evening.
 */
export function adaptiveExpenditure({ entries = [], weights = [], profile = null, now = Date.now() } = {}) {
  const today = dayKey(now);
  const fromDay = dayKey(now - REQUIREMENTS.windowDays * DAY_MS);

  const intake = dailyIntake(entries).filter((d) => d.day >= fromDay && d.day < today);
  const logged = intake.filter((d) => d.calories >= MIN_PLAUSIBLE_KCAL);

  // The window starts where logging did, so it grows with a new user rather
  // than counting the weeks before they installed the app as days missed.
  const startDay = logged.length ? logged[0].day : fromDay;
  const spanDays = Math.round((Date.parse(today) - Date.parse(startDay)) / DAY_MS);

  // Weights over the same days as the intake: the identity only holds when
  // both sides describe the same period. Filtered by the day a reading is
  // about, not when it was typed, so a back-filled reading lands where it
  // belongs.
  const recentWeights = (weights || []).filter((w) => {
    const day = typeof w?.day === 'string' ? w.day : null;
    const t = Date.parse(w?.at ?? w?.measured_at ?? w?.date);
    const key = day || (Number.isFinite(t) ? dayKey(t) : null);
    return key !== null && key >= startDay && key <= today;
  });

  const coverage = spanDays > 0 ? logged.length / spanDays : 0;
  const trend = weightTrend(recentWeights, {
    minReadings: REQUIREMENTS.minWeighings,
    minSpanDays: REQUIREMENTS.minWeightSpanDays
  });

  // Everything that is missing is reported at once, so the UI can tell someone
  // what to do rather than repeating "not enough data" as each gap clears.
  const missing = [];
  if (logged.length < REQUIREMENTS.minLoggedDays) {
    missing.push({ what: 'logged_days', have: logged.length, need: REQUIREMENTS.minLoggedDays });
  }
  if (coverage < REQUIREMENTS.minCoverage) {
    missing.push({
      what: 'coverage',
      have: Math.round(coverage * 100),
      need: Math.round(REQUIREMENTS.minCoverage * 100)
    });
  }
  if (!trend) {
    missing.push({
      what: 'weighings',
      have: recentWeights.length,
      need: REQUIREMENTS.minWeighings
    });
  }

  const formula = maintenanceEnergy(profile);

  // The days the estimate looked at, so the weight chart can fit its line to
  // the same weigh-ins. A chart fitted to every reading disagreed with the
  // figure beside it whenever older readings pulled the line another way.
  const window = { from: startDay, days: spanDays };

  if (missing.length || !trend?.slopeSeKgPerDay) {
    return {
      method: 'formula',
      ...(formula || {}),
      available: Boolean(formula),
      // What the *profile* still lacks, as opposed to what the measurement
      // lacks. The two are different asks and get answered in different
      // places, so they are reported separately.
      profileMissing: missingForMaintenance(profile),
      missing,
      window,
      progress: {
        loggedDays: logged.length,
        neededDays: REQUIREMENTS.minLoggedDays,
        weighings: recentWeights.length,
        neededWeighings: REQUIREMENTS.minWeighings,
        coveragePct: Math.round(coverage * 100)
      }
    };
  }

  const meanIntake = logged.reduce((a, d) => a + d.calories, 0) / logged.length;
  const meanBand = logged.reduce((a, d) => a + d.band * d.calories, 0)
    / logged.reduce((a, d) => a + d.calories, 0);

  const measuredKcal = meanIntake - trend.slopeKgPerDay * KCAL_PER_KG;

  // Three independent sources of error, combined in quadrature:
  //   the mean of a noisy intake estimate, which shrinks with more days;
  //   the slope of the weight trend;
  //   the energy density of the tissue being gained or lost.
  const intakeSe = (meanIntake * meanBand) / Math.sqrt(logged.length);
  const slopeSe = trend.slopeSeKgPerDay * KCAL_PER_KG;
  const densitySe = Math.abs(trend.slopeKgPerDay) * KCAL_PER_KG * KCAL_PER_KG_UNCERTAINTY;
  // Stretched for a weight fit on few readings, whose own spread is still a
  // guess. The t-multiplier comes from the weight fit because it is the
  // smallest sample in play and therefore the binding constraint.
  const measuredSd = Math.sqrt(intakeSe ** 2 + slopeSe ** 2 + densitySe ** 2) * tStretch(trend.df);

  // The measurement and the formula are two independent readings of the same
  // quantity, so each counts in proportion to how sure it is. Early on the
  // weight trend is barely known and the formula carries the figure; as the
  // weeks add up the measurement takes it over. Without this the figure
  // jumped from formula to raw measurement on the day the requirements were
  // met, and its band often to several times the formula's width.
  const formulaSd = formula ? formula.kcal * FORMULA_SD : null;
  const measuredWeight = formulaSd ? formulaSd ** 2 / (formulaSd ** 2 + measuredSd ** 2) : 1;
  const kcal = formulaSd
    ? measuredWeight * measuredKcal + (1 - measuredWeight) * formula.kcal
    : measuredKcal;
  const sd = formulaSd
    ? Math.sqrt(1 / (1 / measuredSd ** 2 + 1 / formulaSd ** 2))
    : measuredSd;

  return {
    method: 'measured',
    available: true,
    kcal: Math.round(kcal),
    // One standard deviation either side, the same reading of "likely" as the
    // formula's own band, so the range narrows smoothly as evidence arrives
    // instead of widening on the day measuring begins.
    low: Math.round(kcal - sd),
    high: Math.round(kcal + sd),
    estimated: true,
    // The measurement on its own, before the formula was weighed in.
    measured: {
      kcal: Math.round(measuredKcal),
      low: Math.round(measuredKcal - measuredSd),
      high: Math.round(measuredKcal + measuredSd)
    },
    // How much of the figure is the measurement, 0..1. The rest is formula.
    measuredShare: Math.round(measuredWeight * 100) / 100,
    window,
    basis: {
      windowDays: spanDays,
      loggedDays: logged.length,
      coveragePct: Math.round(coverage * 100),
      meanIntake: Math.round(meanIntake),
      weighings: trend.readings,
      weightSpanDays: trend.spanDays,
      slopeKgPerWeek: Math.round(trend.slopeKgPerWeek * 1000) / 1000,
      assumedKcalPerKg: KCAL_PER_KG
    },
    // The formula estimate alongside, because a large disagreement between the
    // two is itself information -- usually that intake is being under-logged.
    formula
  };
}

/**
 * Student's t at one standard deviation's coverage (68%), relative to the
 * normal. A fit on five readings is less sure of its own spread than one on
 * forty, and this is how much wider that makes it.
 */
function tStretch(df) {
  const table = { 1: 1.84, 2: 1.32, 3: 1.20, 4: 1.14, 5: 1.11, 6: 1.09, 7: 1.08, 8: 1.07, 10: 1.05, 15: 1.03, 20: 1.03, 30: 1.02 };
  const keys = Object.keys(table).map(Number).sort((a, b) => a - b);
  for (const k of keys) if (df <= k) return table[k];
  return 1;
}
