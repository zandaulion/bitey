import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { plural } from '../web/i18n.js';

// English is the key language and has no catalogue, so its singulars come
// from i18n.js itself. PrimeTestLab report 7991 (M-01) found "1 weigh-ins".
const SO_FAR = '{0} weigh-ins so far. {1} more, spread over a week, and a trend line appears.';

test('English counts one weigh-in in the singular', () => {
  assert.equal(plural(SO_FAR, 1, 2), '1 weigh-in so far. 2 more, spread over a week, and a trend line appears.');
  assert.equal(plural(SO_FAR, 2, 1), '2 weigh-ins so far. 1 more, spread over a week, and a trend line appears.');
  assert.equal(plural('{0} more weigh-ins', 1), '1 more weigh-in');
  assert.equal(plural('trend needs {0} more days', 1), 'trend needs 1 more day');
});

test('every counted English string has its singular', () => {
  // Any key passed to plural() whose English is a plural noun must have a
  // singular in i18n.js, or the bug comes back for that string.
  const app = fs.readFileSync(new URL('../web/app.js', import.meta.url), 'utf8');
  const keys = [...app.matchAll(/plural\(\s*'([^']+)'/g)].map((m) => m[1]);
  assert.ok(keys.length > 0);
  for (const key of keys) {
    if (!/\{0\} (more )?(weigh-ins|days|minutes)|\{0\} minutes/.test(key)) continue;
    assert.notEqual(plural(key, 1), plural(key, 2).replace('2', '1'), `no English singular for: ${key}`);
  }
});

test('no catalogue repeats the plural in its singular form for weigh-ins so far', () => {
  for (const file of fs.readdirSync(new URL('../web/i18n/', import.meta.url))) {
    const lang = file.replace('.json', '');
    const forms = JSON.parse(fs.readFileSync(new URL(`../web/i18n/${file}`, import.meta.url), 'utf8'))[SO_FAR];
    if (!forms || typeof forms !== 'object') continue;
    const rules = new Intl.PluralRules(lang);
    const one = forms[rules.select(1)] || forms.other;
    const five = forms[rules.select(5)] || forms.other;
    // Languages whose singular differs must say it differently; the ones
    // with a single form (or a count-neutral phrasing) have only `other`.
    if (rules.select(1) !== rules.select(5) && Object.keys(forms).length > 1) {
      assert.notEqual(one.replace('{0}', 'N'), five.replace('{0}', 'N'), `${lang}: one and many read the same`);
    }
  }
});
