/**
 * BFF entry — tracing must initialize before Express loads.
 */
const { W3C_TRACE_HEADERS, odigosInstrumented } = require('./tracing');

const express = require('express');
const fs = require('fs');
const path = require('path');
const { createProxyMiddleware } = require('http-proxy-middleware');

const app = express();
const PORT = process.env.PORT || 8080;
const API_GATEWAY_URL = process.env.API_GATEWAY_URL || 'http://api-gateway:8081';
const RUM_ENABLED = String(process.env.RUM_ENABLED || '').toLowerCase() === 'true';
const RUM_PROXY_TARGET = process.env.RUM_PROXY_TARGET || 'http://127.0.0.1:9998';
const CLICKSTACK_ENABLED = String(process.env.CLICKSTACK_ENABLED || '').toLowerCase() === 'true';
const CLICKSTACK_PROXY_TARGET = process.env.CLICKSTACK_PROXY_TARGET || 'http://127.0.0.1:4318';
const CLICKSTACK_API_KEY = process.env.CLICKSTACK_API_KEY || '';
const CLICKSTACK_SERVICE = process.env.CLICKSTACK_SERVICE || 'odigear-frontend';

const INDEX_PATH = path.join(__dirname, 'public', 'index.html');
const PUBLIC_DIR = path.join(__dirname, 'public');
const RUM_BUNDLE_PATH = path.join(__dirname, 'public', 'rum.bundle.js');
const CLICKSTACK_BUNDLE_PATH = path.join(__dirname, 'public', 'clickstack.bundle.js');
/** Bust browser cache whenever the shipped bundle file changes (e.g. new image). */
const RUM_BUNDLE_QUERY = (() => {
  try {
    return `?v=${Math.floor(fs.statSync(RUM_BUNDLE_PATH).mtimeMs)}`;
  } catch {
    return `?v=${Date.now()}`;
  }
})();
const CLICKSTACK_BUNDLE_QUERY = (() => {
  try {
    return `?v=${Math.floor(fs.statSync(CLICKSTACK_BUNDLE_PATH).mtimeMs)}`;
  } catch {
    return `?v=${Date.now()}`;
  }
})();

/** Query string for /app.js and /style.css so deploys invalidate browser cache without a hard refresh. */
function publicAssetQuery() {
  try {
    const mtimes = ['app.js', 'style.css'].map((f) => fs.statSync(path.join(PUBLIC_DIR, f)).mtimeMs);
    return `?v=${Math.floor(Math.max(...mtimes))}`;
  } catch {
    return `?v=${Date.now()}`;
  }
}

function buildRumInjection() {
  if (!RUM_ENABLED) {
    return '<script>window.__ODIMALL_RUM__={enabled:false};</script>';
  }
  const url = '/faro';
  return `<script>window.__ODIMALL_RUM__={enabled:true,url:${JSON.stringify(url)},version:"1.0.0"};</script><script src="/rum.bundle.js${RUM_BUNDLE_QUERY}"></script>`;
}

function buildClickstackInjection() {
  if (!CLICKSTACK_ENABLED) {
    return '<script>window.__ODIMALL_CLICKSTACK__={enabled:false};</script>';
  }
  if (!CLICKSTACK_API_KEY) {
    console.warn('CLICKSTACK_ENABLED is true but CLICKSTACK_API_KEY is not set; ClickStack SDK will not initialize.');
    return '<script>window.__ODIMALL_CLICKSTACK__={enabled:false};</script>';
  }
  const cfg = {
    enabled: true,
    url: '/clickstack',
    apiKey: CLICKSTACK_API_KEY,
    service: CLICKSTACK_SERVICE,
    version: '1.0.0',
    environment: 'demo',
  };
  return `<script>window.__ODIMALL_CLICKSTACK__=${JSON.stringify(cfg)};</script><script src="/clickstack.bundle.js${CLICKSTACK_BUNDLE_QUERY}"></script>`;
}

function buildObservabilityInjection() {
  return buildRumInjection() + buildClickstackInjection();
}

function forwardTraceContext(proxyReq, req) {
  for (const name of W3C_TRACE_HEADERS) {
    const value = req.headers[name];
    if (value) {
      proxyReq.setHeader(name, value);
    }
  }
  // Belt-and-suspenders: inject active OTel context onto the outbound proxy request.
  try {
    const { propagation, context } = require('@opentelemetry/api');
    propagation.inject(context.active(), proxyReq, {
      set(carrier, key, value) {
        carrier.setHeader(key, value);
      },
    });
  } catch {
    // OTel not loaded (ClickStack disabled).
  }
}

app.use('/api', createProxyMiddleware({
  target: API_GATEWAY_URL,
  changeOrigin: true,
  pathRewrite: { '^/api': '' },
  onProxyReq(proxyReq, req) {
    forwardTraceContext(proxyReq, req);
  },
  onError(err, req, res) {
    console.error(`Proxy error: ${err.message}`);
    res.status(502).json({ error: 'API gateway unavailable' });
  }
}));

if (RUM_ENABLED) {
  app.use('/faro', createProxyMiddleware({
    target: RUM_PROXY_TARGET,
    changeOrigin: true,
    pathRewrite: { '^/faro': '' },
    onError(err, req, res) {
      console.error(`Faro proxy error: ${err.message}`);
      res.status(502).send('Faro collector unavailable');
    }
  }));
}

if (CLICKSTACK_ENABLED) {
  app.use('/clickstack', createProxyMiddleware({
    target: CLICKSTACK_PROXY_TARGET,
    changeOrigin: true,
    pathRewrite: { '^/clickstack': '' },
    onProxyReq(proxyReq) {
      if (CLICKSTACK_API_KEY && !proxyReq.getHeader('authorization')) {
        proxyReq.setHeader('Authorization', CLICKSTACK_API_KEY);
      }
    },
    onError(err, req, res) {
      console.error(`ClickStack proxy error: ${err.message}`);
      res.status(502).send('ClickStack collector unavailable');
    }
  }));
}

// Faro bundle must not be sticky-cached across deploys (service name / SDK changes).
app.get('/rum.bundle.js', (req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  res.sendFile(RUM_BUNDLE_PATH);
});

app.get('/clickstack.bundle.js', (req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  res.sendFile(CLICKSTACK_BUNDLE_PATH);
});

// SPA shell assets: avoid stale app.js after image upgrades (browser default-cache + Docker tag reuse).
app.get('/app.js', (req, res) => {
  res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate');
  res.type('application/javascript');
  res.sendFile(path.join(PUBLIC_DIR, 'app.js'));
});
app.get('/style.css', (req, res) => {
  res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate');
  res.type('text/css');
  res.sendFile(path.join(PUBLIC_DIR, 'style.css'));
});

app.use(express.static(PUBLIC_DIR, { index: false }));

app.get('*', (req, res) => {
  if (req.path.startsWith('/api') || req.path.startsWith('/faro') || req.path.startsWith('/clickstack')) {
    return res.status(404).send('Not found');
  }
  const q = publicAssetQuery();
  const html = fs
    .readFileSync(INDEX_PATH, 'utf8')
    .replace(/href="\/style\.css(\?[^"]*)?"/, `href="/style.css${q}"`)
    .replace(/src="\/app\.js(\?[^"]*)?"/, `src="/app.js${q}"`)
    .replace('<!-- ODIMALL_RUM_CONFIG -->', buildObservabilityInjection());
  res.type('html').setHeader('Cache-Control', 'no-store, no-cache, must-revalidate');
  res.send(html);
});

app.listen(PORT, () => {
  console.log(`Frontend server running on port ${PORT}`);
  console.log(`Proxying /api/* → ${API_GATEWAY_URL}`);
  if (RUM_ENABLED) {
    console.log(`RUM (Faro) enabled; proxying /faro/* → ${RUM_PROXY_TARGET}`);
  }
  if (CLICKSTACK_ENABLED) {
    console.log(`ClickStack (HyperDX) enabled; proxying /clickstack/* → ${CLICKSTACK_PROXY_TARGET}`);
    if (!CLICKSTACK_API_KEY || CLICKSTACK_API_KEY.includes('replace-me')) {
      console.warn('CLICKSTACK_API_KEY is missing or still a Helm placeholder — session replay and browser traces will not ingest.');
    }
  }
  if (odigosInstrumented()) {
    console.log('Odigos eBPF instrumentation active on frontend BFF (LD_PRELOAD).');
  }
});
