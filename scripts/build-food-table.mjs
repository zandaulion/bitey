#!/usr/bin/env node
// Builds the bundled generic-food table from USDA FoodData Central and
// ANSES's CIQUAL.
//
// Why bundle at all: Open Food Facts is excellent by barcode and poor by name
// -- searching "banana" there returns banana chips and banana yoghurt before
// fruit. USDA fixes that, but its API needs a key, and a key cannot ship
// inside an APK where anyone can extract it. A table shipped with the app
// needs no key, no network and no rate limit, and it makes the free tier
// genuinely offline rather than merely serverless.
//
// Composition of a generic food does not drift -- chicken breast has the
// protein it had in 2018 -- so this is rebuilt when convenient, not on a
// schedule.
//
//   node scripts/build-food-table.mjs <out.sqlite> //     --foundation <foundation.json> --sr <sr_legacy.json> //     --fndds <surveyDownload.json> --ciqual <dir with ciqual_alim.xml, ciqual_compo.xml> //     --names scripts/food-names.json
//
// Sources, in the order they win a shared English name:
//   Foundation, SR Legacy  USDA ingredients and raw foods (public domain)
//   FNDDS                  USDA foods and dishes "as eaten", with the
//                          portion people typically eat (public domain)
//   CIQUAL                 ANSES, foods eaten in France, French and English
//                          names (Licence Ouverte / Etalab 2.0)
//   scripts/food-names.json curated names for common foods in every app
//                          language, linked to the foods above by name
//
// Parsed as a stream. The SR Legacy file is 201 MB and JSON.parse would want
// well over a gigabyte for it; this build should not be the reason a laptop
// starts swapping.

import fs from 'node:fs';
import { DatabaseSync } from 'node:sqlite';
import { isPlausible, tokenise } from '../core/foods.js';

const [out, ...rest] = process.argv.slice(2);
const KINDS = { '--foundation': 'foundation', '--sr': 'sr', '--fndds': 'fndds', '--ciqual': 'ciqual', '--names': 'names' };
const inputs = [];
for (let i = 0; i < rest.length; i += 2) {
  const kind = KINDS[rest[i]];
  if (!kind || !rest[i + 1]) {
    console.error(`unknown or incomplete argument: ${rest[i]}`);
    process.exit(1);
  }
  inputs.push([kind, rest[i + 1]]);
}
if (!out || !inputs.length) {
  console.error('usage: build-food-table.mjs <out.sqlite> --foundation f.json --sr s.json --fndds survey.json --ciqual dir');
  process.exit(1);
}

/** The search column: folded words, space separated, as core/foods.js and
 * Android's query read them. */
const searchKey = (s) => tokenise(s).join(' ');

/**
 * Yields each element of the first top-level JSON array in the file, as text.
 *
 * Scans with an index and only compacts the buffer once per chunk, rather than
 * re-slicing it after every object. The first version sliced on each yield and
 * lost objects across chunk boundaries -- it read 340 foods as 32 -- so this
 * one is checked against a known count in the tests.
 *
 * String and escape state are tracked so a brace inside a food name cannot be
 * mistaken for structure.
 */
async function* streamArray(file) {
  const stream = fs.createReadStream(file, { encoding: 'utf8', highWaterMark: 1 << 20 });
  let buf = '';
  let pos = 0;          // where the scan has reached
  let objStart = -1;    // start of the object being read, if any
  let started = false, depth = 0, inString = false, escaped = false;

  for await (const chunk of stream) {
    buf += chunk;

    while (pos < buf.length) {
      const c = buf[pos];

      if (inString) {
        if (escaped) escaped = false;
        else if (c === '\\') escaped = true;
        else if (c === '"') inString = false;
      } else if (c === '"') {
        inString = true;
      } else if (!started) {
        if (c === '[') started = true;
      } else if (c === '{') {
        if (depth === 0) objStart = pos;
        depth++;
      } else if (c === '}') {
        depth--;
        if (depth === 0 && objStart >= 0) {
          yield buf.slice(objStart, pos + 1);
          objStart = -1;
        }
      }
      pos++;
    }

    // Drop what has been consumed. Anything belonging to an object still being
    // read has to survive into the next chunk, so compaction starts there.
    const keepFrom = objStart >= 0 ? objStart : pos;
    if (keepFrom > 0) {
      buf = buf.slice(keepFrom);
      pos -= keepFrom;
      if (objStart >= 0) objStart = 0;
    }
  }
}

// ------------------------------------------------------------------ USDA

const NUTRIENT = { 1008: 'calories', 1003: 'protein', 1004: 'fat', 1005: 'carbs', 1079: 'fiber' };
const KJ = 1062;

const round1 = (n) => Math.round(n * 10) / 10;

/**
 * FNDDS records how much of a food people actually eat in one go, as the
 * portion "Quantity not specified" -- the amount a survey respondent meant
 * when they did not say. That is a far better starting weight than 100 g for
 * "Pizza, cheese" or "Coffee, brewed", so it becomes the food's serving.
 * SR Legacy's portions are household measures with no typical one among
 * them, so its foods keep the 100 g default.
 */
function typicalPortion(food) {
  const portions = food.foodPortions || [];
  const typical = portions.find((p) => /quantity not specified/i.test(p.portionDescription || ''));
  const g = Number(typical?.gramWeight);
  return Number.isFinite(g) && g > 0 ? Math.round(g) : null;
}

function fromUsdaRecord(food, kind) {
  const name = String(food.description || '').trim();
  if (!name) return null;

  const per100 = { calories: null, protein: 0, fat: 0, carbs: 0, fiber: null };
  let kj = null;

  for (const row of food.foodNutrients || []) {
    const id = row?.nutrient?.id;
    const amount = Number(row?.amount);
    if (!Number.isFinite(amount)) continue;
    if (id === KJ) { kj = amount; continue; }
    const key = NUTRIENT[id];
    if (key) per100[key] = amount;
  }

  if (per100.calories === null && kj !== null) per100.calories = kj / 4.184;
  if (per100.calories === null) return null;

  return {
    names: [['en', name]],
    per100: {
      calories: round1(per100.calories), protein: round1(per100.protein), fat: round1(per100.fat),
      carbs: round1(per100.carbs), fiber: per100.fiber === null ? null : round1(per100.fiber)
    },
    servingG: kind === 'fndds' ? typicalPortion(food) : null,
    // The provider, not the dataset: attribution and the result's id need
    // to know whose data it is, and all three USDA datasets are USDA's.
    source: 'usda'
  };
}

async function* usdaFoods(file, kind) {
  for await (const text of streamArray(file)) {
    let food;
    try { food = JSON.parse(text); } catch { yield null; continue; }
    yield fromUsdaRecord(food, kind);
  }
}

// ---------------------------------------------------------------- CIQUAL

/**
 * ANSES's French table, CIQUAL. Its foods are what is eaten in France --
 * breads, cheeses, charcuterie, prepared dishes -- and each has a French and
 * an English name. Licence Ouverte / Etalab 2.0: free reuse with attribution.
 *
 * Values are text: "12,5", "traces", "< 0,1", "-". A trace or a "less than"
 * is counted as nothing, which is the honest reading at the precision a diary
 * works in; "-" is unknown.
 */
const CIQUAL = { 328: 'calories', 333: 'caloriesAlt', 25000: 'protein', 40000: 'fat', 31000: 'carbs', 34100: 'fiber' };

function ciqualValue(text) {
  const t = String(text || '').trim();
  if (!t || t === '-') return null;
  if (/^traces$/i.test(t) || t.startsWith('<')) return 0;
  const n = Number(t.replace(',', '.'));
  return Number.isFinite(n) ? n : null;
}

const xmlText = (s) => s.replace(/&apos;/g, "'").replace(/&quot;/g, '"').replace(/&lt;/g, '<')
  .replace(/&gt;/g, '>').replace(/&amp;/g, '&').trim();

function* ciqualFoods(dir) {
  const alim = fs.readFileSync(`${dir}/ciqual_alim.xml`, 'utf8');
  const compo = fs.readFileSync(`${dir}/ciqual_compo.xml`, 'utf8');

  const values = new Map();
  for (const m of compo.matchAll(/<COMPO>([\s\S]*?)<\/COMPO>/g)) {
    const code = /<alim_code>\s*(\d+)\s*</.exec(m[1])?.[1];
    const constant = /<const_code>\s*(\d+)\s*</.exec(m[1])?.[1];
    const key = CIQUAL[constant];
    if (!code || !key) continue;
    const value = ciqualValue(xmlText(/<teneur>([^<]*)</.exec(m[1])?.[1] || ''));
    if (!values.has(code)) values.set(code, {});
    values.get(code)[key] = value;
  }

  for (const m of alim.matchAll(/<ALIM>([\s\S]*?)<\/ALIM>/g)) {
    const code = /<alim_code>\s*(\d+)\s*</.exec(m[1])?.[1];
    const fr = xmlText(/<alim_nom_fr>([^<]*)</.exec(m[1])?.[1] || '');
    const en = xmlText(/<alim_nom_eng>([^<]*)</.exec(m[1])?.[1] || '');
    const v = values.get(code) || {};
    const calories = v.calories ?? v.caloriesAlt;
    if (!fr || calories === null || calories === undefined) { yield null; continue; }
    yield {
      names: [['fr', fr], ...(en ? [['en', en]] : [])],
      per100: {
        calories: round1(calories), protein: round1(v.protein ?? 0), fat: round1(v.fat ?? 0),
        carbs: round1(v.carbs ?? 0), fiber: v.fiber === null || v.fiber === undefined ? null : round1(v.fiber)
      },
      servingG: null,
      source: 'ciqual'
    };
  }
}

// ----------------------------------------------------------------- build

fs.rmSync(out, { force: true });
const db = new DatabaseSync(out);
db.exec(`
  CREATE TABLE foods (
    id         INTEGER PRIMARY KEY,
    kcal       REAL NOT NULL,
    protein    REAL NOT NULL,
    fat        REAL NOT NULL,
    carbs      REAL NOT NULL,
    fiber      REAL,
    serving_g  REAL,
    source     TEXT NOT NULL
  );
  -- One row per name per language. A food is found by any of its names and
  -- shown in the reader's language when the table has one.
  -- rank orders the names a food has in one language: 0 a curated name
  -- shown to the reader, 1 the source table's own name, 2 an alias that
  -- finds the food but is not shown.
  CREATE TABLE names (
    food_id  INTEGER NOT NULL REFERENCES foods(id),
    lang     TEXT NOT NULL,
    name     TEXT NOT NULL,
    search   TEXT NOT NULL,
    rank     INTEGER NOT NULL DEFAULT 1
  );
  CREATE INDEX idx_names_search ON names(search);
  CREATE INDEX idx_names_food ON names(food_id, lang);
`);

const insertFood = db.prepare(
  'INSERT INTO foods (kcal, protein, fat, carbs, fiber, serving_g, source) VALUES (?, ?, ?, ?, ?, ?, ?)');
const insertName = db.prepare('INSERT INTO names (food_id, lang, name, search, rank) VALUES (?, ?, ?, ?, ?)');
const hasName = db.prepare('SELECT 1 FROM names WHERE food_id = ? AND lang = ?');

// English search key -> food id. The first source listed wins a name; a later
// food under the same English name lends it the names it has in other
// languages instead of becoming a second row.
const byEnglish = new Map();
let read = 0, kept = 0, merged = 0, rejected = 0, duplicate = 0;

db.exec('BEGIN');
for (const [kind, path] of inputs) {
  if (kind === 'names') continue;
  let fileKept = 0;
  const rows = kind === 'ciqual' ? ciqualFoods(path) : usdaFoods(path, kind);
  for await (const row of rows) {
    read++;
    // The same physical check the live lookups use, so a broken row cannot
    // reach the bundled table either.
    if (!row || !isPlausible(row.per100)) { rejected++; continue; }

    const english = row.names.find(([lang]) => lang === 'en')?.[1];
    const key = english ? searchKey(english) : null;
    if (key && byEnglish.has(key)) {
      const id = byEnglish.get(key);
      let lent = false;
      for (const [lang, name] of row.names) {
        if (lang === 'en' || hasName.get(id, lang)) continue;
        insertName.run(id, lang, name, searchKey(name), 1);
        lent = true;
      }
      if (lent) merged++; else duplicate++;
      continue;
    }

    const { lastInsertRowid } = insertFood.run(row.per100.calories, row.per100.protein, row.per100.fat,
      row.per100.carbs, row.per100.fiber, row.servingG, row.source);
    const id = Number(lastInsertRowid);
    for (const [lang, name] of row.names) insertName.run(id, lang, name, searchKey(name), 1);
    if (key) byEnglish.set(key, id);
    kept++; fileKept++;
  }
  console.log(`  ${kind} ${path.split(/[\\/]/).pop()}: kept ${fileKept}`);
}
// Curated names, after every food exists. Each entry names its food exactly
// as a source table does; one that matches nothing is a mistake in the file,
// and the build stops rather than shipping it silently unlinked.
const findFood = db.prepare(
  "SELECT food_id FROM names WHERE name = ? ORDER BY (lang = 'en') DESC, food_id LIMIT 1");
const hasExact = db.prepare('SELECT 1 FROM names WHERE food_id = ? AND lang = ? AND search = ?');
let curated = 0, curatedNames = 0;
const unmatched = [];
for (const [kind, path] of inputs) {
  if (kind !== 'names') continue;
  for (const entry of JSON.parse(fs.readFileSync(path, 'utf8'))) {
    const hit = findFood.get(entry.food);
    if (!hit) { unmatched.push(entry.food); continue; }
    curated++;
    for (const [lang, names] of Object.entries(entry)) {
      if (lang === 'food' || !Array.isArray(names)) continue;
      names.forEach((name, i) => {
        const key = searchKey(name);
        if (!key || hasExact.get(hit.food_id, lang, key)) return;
        // English keeps the source's name on screen; the curated English
        // names are only there to be found by ("fries", "porridge").
        insertName.run(hit.food_id, lang, name, key, lang === 'en' || i > 0 ? 2 : 0);
        curatedNames++;
      });
    }
  }
  console.log(`  names ${path.split(/[\\/]/).pop()}: ${curated} foods, ${curatedNames} names`);
}
if (unmatched.length) {
  db.exec('ROLLBACK');
  console.error(`\n${unmatched.length} curated entries match no food:\n  ${unmatched.join('\n  ')}`);
  process.exit(1);
}

db.exec('COMMIT');
db.exec('VACUUM');

const size = fs.statSync(out).size;
console.log(`\nread ${read}, kept ${kept}, merged ${merged} into an existing food, ` +
  `dropped ${rejected} implausible or empty, ${duplicate} duplicates`);
console.log(`${out} — ${(size / 1024).toFixed(0)} KB`);
