/**
 * Plate — interface language.
 *
 * The English string is the key. Keeping it as the key makes call sites
 * readable (`t('Save this recovery code')` says what it renders) and means
 * English needs no catalogue at all: a missing translation falls through to
 * the key, which is already the correct English. The cost is that rewording
 * English orphans a translation, so `npm run i18n:check` lists every bundled
 * catalogue key that no longer matches.
 *
 * Placeholders are {0}, {1} — positional, because Romanian reorders clauses
 * and a translator must be free to move them.
 */
'use strict';

export const LOCALES = ['en', 'ar', 'zh', 'fr', 'de', 'hi', 'ja', 'ko', 'pt', 'ro', 'es', 'uk'];
export const LOCALE_NAMES = {
  en: 'English',
  ar: 'العربية',
  zh: '中文',
  fr: 'Français',
  de: 'Deutsch',
  hi: 'हिन्दी',
  ja: '日本語',
  ko: '한국어',
  pt: 'Português',
  ro: 'Română',
  es: 'Español',
  uk: 'Українська'
};
const STORAGE_KEY = 'plate-locale';

let strings = {};
let current = 'en';

/** Saved choice first, then the browser's, then English. */
export function preferred() {
  const saved = localStorage.getItem(STORAGE_KEY);
  if (saved && LOCALES.includes(saved)) return saved;
  for (const tag of navigator.languages || [navigator.language || 'en']) {
    const base = String(tag).toLowerCase().split('-')[0];
    if (LOCALES.includes(base)) return base;
  }
  return 'en';
}

export async function load(locale) {
  current = LOCALES.includes(locale) ? locale : preferred();
  if (current === 'en') {
    strings = {};
  } else {
    try {
      const res = await fetch(`/i18n/${current}.json`);
      strings = res.ok ? await res.json() : {};
    } catch {
      // A catalogue that will not load leaves every key falling through to
      // its English text, which is a readable app rather than a broken one.
      strings = {};
    }
  }
  // Set direction at the document root so Arabic layouts, keyboard focus and
  // punctuation all follow the reader's direction. Every other locale stays
  // explicitly LTR after a user changes away from Arabic.
  document.documentElement.lang = current;
  document.documentElement.dir = current === 'ar' ? 'rtl' : 'ltr';
  return current;
}

export async function setLocale(locale) {
  localStorage.setItem(STORAGE_KEY, locale);
  return load(locale);
}

export const locale = () => current;

function fill(template, args) {
  return String(template).replace(/\{(\d+)\}/g, (_, i) => {
    const v = args[Number(i)];
    return v === undefined || v === null ? '' : String(v);
  });
}

/** Translate, falling back to the key — which is the English wording. */
export function t(key, ...args) {
  const found = strings[key];
  const template = typeof found === 'string' ? found : key;
  return args.length ? fill(template, args) : template;
}

/**
 * English's own singular for each counted string. English has no catalogue --
 * the key is the English -- and a key can only be written one way, in the
 * plural, so without these one weigh-in read "1 weigh-ins" (PrimeTestLab
 * report 7991, M-01). Every key passed to plural() whose English changes in
 * the singular belongs here.
 */
const ENGLISH_ONE = {
  'trend needs {0} more weigh-ins': 'trend needs {0} more weigh-in',
  'trend needs {0} more days': 'trend needs {0} more day',
  '{0} weigh-ins so far. {1} more, spread over a week, and a trend line appears.':
    '{0} weigh-in so far. {1} more, spread over a week, and a trend line appears.',
  '{0} weigh-ins so far. Spread over a week, and a trend line appears.':
    '{0} weigh-in so far. Spread over a week, and a trend line appears.',
  '{0} more days logged': '{0} more day logged',
  '{0} more weigh-ins': '{0} more weigh-in',
  'Open Plate there, choose "Link a device", and type this in. It expires in {0} minutes and works once.':
    'Open Plate there, choose "Link a device", and type this in. It expires in {0} minute and works once.'
};

/**
 * Languages use different plural categories. Intl owns the grammar rule, so a
 * catalogue supplies the forms it has and the renderer chooses the right one.
 */
export function plural(key, n, ...args) {
  const forms = strings[key];
  if (!forms || typeof forms !== 'object') {
    // No plural forms in the catalogue: either English, or a catalogue that
    // gives one sentence for every count. Only the first falls back to the
    // English singular.
    if (!forms && ENGLISH_ONE[key] && new Intl.PluralRules('en').select(n) === 'one') {
      return fill(ENGLISH_ONE[key], [n, ...args]);
    }
    return t(key, n, ...args);
  }
  const rule = new Intl.PluralRules(current).select(n);
  const template = forms[rule] || forms.other || Object.values(forms)[0] || key;
  return fill(template, [n, ...args]);
}

/**
 * Applies the catalogue to markup, so static labels live in the HTML rather
 * than in a table of assignments that has to be kept in step with it.
 *
 *   data-i18n              → textContent
 *   data-i18n-placeholder  → placeholder
 *   data-i18n-label        → aria-label
 *   data-i18n-title        → title
 *
 * Re-running it re-translates, which is what makes switching language without
 * a reload possible: the attribute still holds the English key.
 */
export function applyToDom(root = document) {
  for (const el of root.querySelectorAll('[data-i18n]')) {
    el.textContent = t(el.dataset.i18n);
  }
  for (const [attr, prop] of [
    ['i18nPlaceholder', 'placeholder'],
    ['i18nLabel', 'aria-label'],
    ['i18nTitle', 'title']
  ]) {
    const selector = `[data-${attr.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase())}]`;
    for (const el of root.querySelectorAll(selector)) {
      el.setAttribute(prop === 'placeholder' ? 'placeholder' : prop, t(el.dataset[attr]));
    }
  }
}

/** Locale-aware formatting, so 1.234,5 renders correctly in Romanian. */
export const fmtNum = (n, digits = 0) =>
  new Intl.NumberFormat(current, {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits
  }).format(n);

export const fmtDate = (date, opts) =>
  new Date(date).toLocaleDateString(current, opts);
