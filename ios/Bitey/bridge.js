// The same `PlateNative` object Android injects, so the page needs no iOS
// branch. Only what this build implements is defined: the page already checks
// for each capability and hides what is missing.
(() => {
  const call = (method, ...args) => {
    window.webkit.messageHandlers.plate.postMessage({ method, args: args.map(String) });
  };
  const send = (...methods) => Object.fromEntries(
    methods.map((name) => [name, (...args) => call(name, ...args)])
  );

  window.PlateNative = {
    platform: () => 'ios',
    isLocalBuild: () => true,
    aiEntitlement: () => window.__plateAiStatus || 'inactive',
    canPinWidget: () => false,
    canAddQuickTile: () => false,
    setPrimaryActionsVisible: () => {},
    ...send(
      'readCachedFood', 'writeCachedFood', 'searchGenericFoods',
      'localDiary', 'localPhoto', 'capturePhoto', 'requestAiAccess', 'purchaseAiOffer',
      'refreshAiPurchase', 'manageAiSubscription', 'analysePhoto',
      'exportBackup', 'importBackup', 'scanBarcode', 'lookupOpenFoodFacts',
      'openPrivacyNotice', 'openTerms', 'setPrimaryActionLabels',
      'setWidgetSnapshot', 'setWidgetBitey', 'log'
    ),
  };

  // Capturing, because a script that fails to load does not bubble.
  addEventListener('error', (event) => {
    const source = event.target?.src || event.target?.href || event.filename;
    call('log', `error: ${event.message || 'load failed'} at ${source}:${event.lineno || 0}`);
  }, true);
  addEventListener('unhandledrejection', (event) => {
    call('log', `rejection: ${event.reason?.stack || event.reason}`);
  });
})();
