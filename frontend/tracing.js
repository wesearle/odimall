/**
 * BFF trace propagation for /api → api-gateway.
 * When Odigos instruments this pod (LD_PRELOAD), skip a second NodeSDK — Odigos exports
 * BFF spans; ClickStack browser SDK handles session replay + browser traces.
 */
const W3C_TRACE_HEADERS = ['traceparent', 'tracestate', 'baggage'];

function odigosInstrumented() {
  return Boolean(process.env.LD_PRELOAD && String(process.env.LD_PRELOAD).includes('odigos'));
}

function clickstackBffTracingEnabled() {
  const enabled = String(process.env.CLICKSTACK_ENABLED || '').toLowerCase() === 'true';
  const apiKey = (process.env.CLICKSTACK_API_KEY || '').trim();
  const placeholder = apiKey.includes('replace-me');
  return enabled && apiKey && !placeholder && !odigosInstrumented();
}

if (clickstackBffTracingEnabled()) {
  const { NodeSDK } = require('@opentelemetry/sdk-node');
  const { OTLPTraceExporter } = require('@opentelemetry/exporter-trace-otlp-http');
  const { HttpInstrumentation } = require('@opentelemetry/instrumentation-http');
  const { Resource } = require('@opentelemetry/resources');
  const { ATTR_SERVICE_NAME } = require('@opentelemetry/semantic-conventions');

  const target = (process.env.CLICKSTACK_PROXY_TARGET || 'http://127.0.0.1:4318').replace(/\/$/, '');
  const sdk = new NodeSDK({
    resource: new Resource({
      [ATTR_SERVICE_NAME]: process.env.CLICKSTACK_BFF_SERVICE || 'odigear-bff',
    }),
    traceExporter: new OTLPTraceExporter({
      url: `${target}/v1/traces`,
      headers: { Authorization: process.env.CLICKSTACK_API_KEY },
    }),
    instrumentations: [
      new HttpInstrumentation({
        ignoreIncomingRequestHook: (req) => !(req.url || '').startsWith('/api'),
      }),
    ],
  });
  sdk.start();
  process.on('SIGTERM', () => {
    sdk.shutdown().catch(() => {});
  });
}

module.exports = { W3C_TRACE_HEADERS, odigosInstrumented };
