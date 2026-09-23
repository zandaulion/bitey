// Copies the shared analysis rules into functions/ before a deploy.
//
// Firebase uploads the functions directory alone, so a relative import
// reaching up into core/ deploys fine and then dies at cold start on
// ERR_MODULE_NOT_FOUND. The copies are committed rather than generated at
// deploy time only, so what runs in the cloud is visible in the repository and
// a drift test can fail loudly when the two disagree.
//
// core/ is the source of truth. Never edit functions/core/ by hand.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/** Only files with no imports of their own travel; anything that grows a
 * dependency has to be added here deliberately, not discovered in production. */
export const SYNCED = [
  'core/analysis/prompt.js',
  'core/analysis/estimate.js',
  'core/ai/gate.js'
];

const banner = (from) =>
  `// GENERATED COPY of ${from} -- do not edit.\n` +
  `// Run: node scripts/sync-functions-core.mjs\n`;

export function contentFor(relative) {
  return banner(relative) + fs.readFileSync(path.join(root, relative), 'utf8');
}

export function targetFor(relative) {
  return path.join(root, 'functions', relative);
}

export function sync({ check = false } = {}) {
  const stale = [];
  for (const relative of SYNCED) {
    const target = targetFor(relative);
    const wanted = contentFor(relative);
    const current = fs.existsSync(target) ? fs.readFileSync(target, 'utf8') : null;
    if (current === wanted) continue;
    stale.push(relative);
    if (check) continue;
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, wanted);
  }
  return stale;
}

if (process.argv[1] && import.meta.url === new URL(`file://${process.argv[1].replace(/\\/g, '/')}`).href) {
  const stale = sync();
  console.log(stale.length
    ? `synced ${stale.length} file(s) into functions/core: ${stale.join(', ')}`
    : 'functions/core is already up to date');
}
