import { getWebInstrumentations, initializeFaro } from '@grafana/faro-web-sdk';
import { TracingInstrumentation } from '@grafana/faro-web-tracing';

const cfg = typeof window !== 'undefined' ? window.__ODIMALL_RUM__ : null;
if (cfg && cfg.enabled && cfg.url) {
  initializeFaro({
    url: cfg.url,
    app: {
      name: 'browser',
      version: cfg.version || '1.0.0',
    },
    instrumentations: [...getWebInstrumentations(), new TracingInstrumentation()],
  });
}
