// Resolves the page's root-relative imports ('/core/...', '/i18n.js') the way
// WebView does when it serves web/ and core/ from https://plate.local, so a
// module from web/ can be imported by a test unchanged.
const root = new URL('../../', import.meta.url);

export async function resolve(specifier, context, next) {
  if (specifier.startsWith('/core/')) return next(new URL(`.${specifier}`, root).href, context);
  if (specifier.startsWith('/') && !specifier.startsWith('//')) {
    return next(new URL(`web${specifier}`, root).href, context);
  }
  return next(specifier, context);
}
