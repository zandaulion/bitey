(() => {
  // Screenshot-only stand-ins: an active subscription and a fixed estimate
  // after a short wait. No request leaves the phone.
  if (window.__shotStub) return 'already';
  window.__shotStub = true;
  const real = window.PlateNative;
  localStorage.setItem('bitey-ai-text-consent', 'granted');
  window.PlateNative = new Proxy({}, { get: (_, k) => {
    if (k === 'requestAiAccess') return (action, id) => setTimeout(() => window.__plateNativeAiAccessResult(id, JSON.stringify({ status: 'active' })), 0);
    if (k === 'analysePhoto') return (json, id) => setTimeout(() => window.__plateNativeAnalysisResult(id, JSON.stringify({
      httpStatus: 200,
      body: { estimate: { portionSource: 'model', note: 'Assumed a regular wrap and a medium latte with whole milk.', items: [
        { id: 't1', name: 'Chicken salad wrap', grams: 260, source: 'text', per: { calories: 1.9, protein: 0.11, fat: 0.07, carbs: 0.2, fiber: 0.02 } },
        { id: 't2', name: 'Latte', grams: 300, source: 'text', per: { calories: 0.55, protein: 0.033, fat: 0.02, carbs: 0.05, fiber: 0 } }
      ] } }
    })), 2500);
    return typeof real[k] === 'function' ? real[k].bind(real) : real[k];
  } });
  return 'stubbed';
})()
