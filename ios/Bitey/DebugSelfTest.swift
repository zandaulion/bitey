#if DEBUG
import WebKit

/// Exercises every native service through the page's real callbacks. Runs only
/// in debug builds launched with BITEY_SELFTEST=1, and clears what it writes.
enum DebugSelfTest {
    static func run(in webView: WKWebView) {
        let environment = ProcessInfo.processInfo.environment
        if environment["BITEY_LAYOUT"] == "1" { logLayout(of: webView) }
        if environment["BITEY_IMPORT_CHECK"] == "1" { checkImport(in: webView) }
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

    /// Restores BITEY_TEST_BACKUP, for a file another platform wrote, and reports it.
    private static func checkImport(in webView: WKWebView) {
        let script = """
        const reply = (name, start) => new Promise((resolve) => {
          const previous = window[name];
          window[name] = (...args) => { window[name] = previous; resolve(JSON.parse(args[args.length - 1])); };
          start();
        });
        const imported = await reply('__plateNativeBackupResult', () => window.PlateNative.importBackup());
        const list = await reply('__plateNativeDiaryResult', () => window.PlateNative.localDiary('entries.list', '{}', 'c-1'));
        const entry = list.entries?.[0];
        const photo = entry?.photoId ? await fetch(`/local-photo/${entry.photoId}`) : null;
        return JSON.stringify({ imported, id: entry?.id, item: entry?.items?.[0]?.name, photo: photo?.status, type: photo?.headers.get('content-type') });
        """
        DispatchQueue.main.asyncAfter(deadline: .now() + 4) {
            webView.callAsyncJavaScript(script, arguments: [:], in: nil, in: .page) { result in
                switch result {
                case .success(let value): NativeBridge.debugLog("import: \(value)")
                case .failure(let error): NativeBridge.debugLog("import failed: \(error)")
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

    if (typeof window.__selftestBackup === 'undefined') {
      const backup = (operation) => new Promise((resolve) => {
        const previous = window.__plateNativeBackupResult;
        window.__plateNativeBackupResult = (op, payload) => {
          window.__plateNativeBackupResult = previous;
          resolve(JSON.parse(payload));
        };
        window.PlateNative[operation === 'export' ? 'exportBackup' : 'importBackup']();
      });
      await diary('profile.write', { sex: 'f', birthYear: 1990 });
      await diary('weights.write', { day: '2001-02-03', kg: 70.5, at: '2001-02-03T08:00:00.000Z' });
      await native('__plateNativePhotoResult', 'localPhoto', 'write', 'selftest-b', png, 'image/png', 't-photo3');
      await diary('entries.write', { id: 'selftest-2', day: '2001-02-03', sortKey: 9, meal: 'lunch', createdAt: '2001-02-03T12:00:00.000Z', photoId: 'selftest-b', items: [{ name: 'Rice' }], totals: { calories: 200 } });
      await native('__plateNativeFoodCacheResult', 'writeCachedFood', '87654321', '{"name":"y"}');

      r = await backup('export');
      check('backup export', r.ok && r.summary.entries >= 1 && r.summary.photos >= 1, r);

      // Change everything, so a restore that did nothing cannot pass.
      await diary('entries.delete', { id: 'selftest-2' });
      await diary('weights.delete', { day: '2001-02-03' });
      await native('__plateNativePhotoResult', 'localPhoto', 'delete', 'selftest-b', '', '', 't-photo4');
      r = await backup('import');
      check('backup import', r.ok && r.summary.entries >= 1 && r.summary.photos >= 1, r);

      r = await diary('entries.list');
      const restored = r.entries?.find((e) => e.id === 'selftest-2');
      check('restored entry', restored?.meal === 'lunch' && restored?.items?.[0]?.name === 'Rice' && restored?.totals?.calories === 200, r);
      r = await diary('weights.list');
      check('restored weight', r.weights?.some((w) => w.day === '2001-02-03' && w.kg === 70.5), r);
      r = await diary('profile.read');
      check('restored profile', r.profile?.birthYear === 1990, r);
      r = await native('__plateNativeFoodCacheResult', 'readCachedFood', '87654321');
      check('restored barcode', r.foodJson === '{"name":"y"}', r);
      const photo = restored?.photoId ? await fetch(`/local-photo/${restored.photoId}`) : null;
      check('restored photo', photo?.ok && (await photo.blob()).size > 0, restored?.photoId);
      results.backupSummary = JSON.stringify(restored?.photoId);
    }

    const skip = [...document.querySelectorAll('button')].find((b) => b.textContent.trim() === 'Skip' && b.offsetParent);
    skip?.click();
    return JSON.stringify(results);
    """
}
#endif
