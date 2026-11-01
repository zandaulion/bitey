#if DEBUG
import WebKit

/// Exercises every native service through the page's real callbacks. Runs only
/// in debug builds launched with BITEY_SELFTEST=1, and clears what it writes.
enum DebugSelfTest {
    static func run(in webView: WKWebView) {
        let environment = ProcessInfo.processInfo.environment
        if environment["BITEY_LAYOUT"] == "1" { logLayout(of: webView) }
        guard environment["BITEY_SELFTEST"] == "1" else { return }
        // After the page's own start-up requests have settled.
        DispatchQueue.main.asyncAfter(deadline: .now() + 4) {
            webView.callAsyncJavaScript(script, arguments: [:], in: nil, in: .page) { result in
                switch result {
                case .success(let value): NativeBridge.debugLog("selftest: \(value)")
                case .failure(let error): NativeBridge.debugLog("selftest failed: \(error)")
                }
            }
        }
    }

    /// Reports where the first-run screen's parts really land, in CSS pixels.
    private static func logLayout(of webView: WKWebView) {
        let probe = """
        JSON.stringify({ window: [innerWidth, innerHeight], visual: visualViewport?.height,
          parts: ['.welcome-inner', '.welcome-foot'].map((name) => {
            const e = document.querySelector(name), box = e?.getBoundingClientRect(), css = e && getComputedStyle(e);
            return { name, top: box?.top, bottom: box?.bottom, height: box?.height, position: css?.position,
              cssBottom: css?.bottom, scroll: e?.scrollHeight, client: e?.clientHeight };
          }) })
        """
        DispatchQueue.main.asyncAfter(deadline: .now() + 4) {
            webView.evaluateJavaScript(probe) { result, _ in
                NativeBridge.debugLog("layout: \(result ?? "nil")")
            }
        }
    }

    private static let script = """
    const results = {};
    const check = (name, ok, detail) => { results[name] = ok ? 'ok' : `FAIL ${JSON.stringify(detail)}`; };
    const native = (callback, method, ...args) => new Promise((resolve) => {
      const id = args[args.length - 1];
      const previous = window[callback];
      window[callback] = (...reply) => {
        // Barcode replies carry no id; every other reply names its request.
        if (reply.length > 1 && reply[0] !== id) return previous?.(...reply);
        window[callback] = previous;
        resolve(JSON.parse(reply[reply.length - 1]));
      };
      window.PlateNative[method](...args);
    });
    const diary = (op, payload = {}) => native('__plateNativeDiaryResult', 'localDiary', op, JSON.stringify(payload), `t-${op}`);

    let r = await diary('profile.write', { sex: 'f', birthYear: 1990 });
    r = await diary('profile.read');
    check('profile', r.ok && r.profile?.birthYear === 1990, r);

    await diary('weights.write', { day: '2001-02-03', kg: 70.5 });
    r = await diary('weights.list');
    check('weights', r.weights?.some((w) => w.day === '2001-02-03' && w.kg === 70.5), r);
    r = await diary('weights.write', { day: 'bad', kg: 70 });
    check('weights reject bad input', r.ok === false && r.code === 'bad_weight', r);
    await diary('weights.delete', { day: '2001-02-03' });

    const entry = { id: 'selftest-1', day: '2001-02-03', sortKey: 5, name: 'Rice', photoData: 'x' };
    r = await diary('entries.write', entry);
    check('entries strip photoData', r.ok && r.entry.photoData === undefined, r);
    r = await diary('entries.list');
    check('entries list', r.entries?.some((e) => e.id === 'selftest-1'), r);
    r = await diary('entries.delete', { id: 'selftest-1' });
    check('entries delete', r.deleted === true, r);

    const png = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==';
    r = await native('__plateNativePhotoResult', 'localPhoto', 'write', 'selftest-p', png, 'image/png', 't-photo');
    check('photo write', r.ok, r);
    const served = await fetch('/local-photo/selftest-p');
    check('photo served', served.ok && served.headers.get('content-type') === 'image/png', served.status);
    r = await native('__plateNativePhotoResult', 'localPhoto', 'delete', 'selftest-p', '', '', 't-photo2');
    check('photo delete', r.ok, r);

    r = await native('__plateNativeGenericFoodSearchResult', 'searchGenericFoods', 'orez', 'ro', 't-food');
    check('food search ro', r.ok && r.results?.length > 0, r);
    results.foodSample = r.results?.[0]?.name;
    r = await native('__plateNativeGenericFoodSearchResult', 'searchGenericFoods', 'white rice cooked', 'en', 't-food2');
    check('food search en', r.ok && r.results?.length > 0, r);
    r = await native('__plateNativeFoodCacheResult', 'writeCachedFood', '12345678', '{"name":"x"}');
    r = await native('__plateNativeFoodCacheResult', 'readCachedFood', '12345678');
    check('barcode cache', r.ok && r.foodJson === '{"name":"x"}', r);

    const skip = [...document.querySelectorAll('button')].find((b) => b.textContent.trim() === 'Skip' && b.offsetParent);
    skip?.click();
    return JSON.stringify(results);
    """
}
#endif
