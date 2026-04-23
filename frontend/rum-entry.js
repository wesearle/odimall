import { getWebInstrumentations, initializeFaro } from '@grafana/faro-web-sdk';
import { TracingInstrumentation } from '@grafana/faro-web-tracing';

/**
 * @param {Request | { method?: string; url?: string }} request
 * @param {Response | { url?: string } | undefined} result
 */
function describeFetchSpan(request, result) {
  let href = '';
  let method = 'GET';
  if (request instanceof Request) {
    href = request.url;
    method = request.method || 'GET';
  } else if (request && typeof request === 'object') {
    if ('method' in request && request.method) method = String(request.method);
  }
  if (!href && result && typeof result === 'object' && 'url' in result && result.url) {
    href = String(result.url);
  }
  try {
    const u = new URL(href || window.location.href, window.location.origin);
    const path = u.pathname + u.search;
    return { method, path };
  } catch {
    return { method, path: href || 'fetch' };
  }
}

const cfg = typeof window !== 'undefined' ? window.__ODIMALL_RUM__ : null;
if (cfg && cfg.enabled && cfg.url) {
  initializeFaro({
    url: cfg.url,
    app: {
      name: 'browser',
      version: cfg.version || '1.0.0',
    },
    instrumentations: [
      ...getWebInstrumentations(),
      new TracingInstrumentation({
        instrumentationOptions: {
          fetchInstrumentationOptions: {
            applyCustomAttributesOnSpan(span, request, result) {
              const { method, path } = describeFetchSpan(request, result);
              span.updateName(`${method} ${path}`);
              span.setAttribute('odimall.http.route', path);
              if (method === 'POST' && path.replace(/\?.*$/, '') === '/api/orders') {
                span.setAttribute('odimall.ui.action', 'place_order');
              }
            },
          },
        },
      }),
    ],
  });
}
