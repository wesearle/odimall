import HyperDX from '@hyperdx/browser';

const cfg = typeof window !== 'undefined' ? window.__ODIMALL_CLICKSTACK__ : null;
if (cfg && cfg.enabled && cfg.url && cfg.apiKey) {
  HyperDX.init({
    url: cfg.url,
    apiKey: cfg.apiKey,
    service: cfg.service || 'odigear-frontend',
    tracePropagationTargets: [window.location.origin, /\/api/i],
    ignoreUrls: [/\/clickstack/i, /\/faro/i, /\/rum\.bundle/i],
    consoleCapture: true,
    advancedNetworkCapture: true,
    disableReplay: false,
    maskAllInputs: true,
    otelResourceAttributes: {
      'service.version': cfg.version || '1.0.0',
      'deployment.environment': cfg.environment || 'demo',
    },
  });
}
