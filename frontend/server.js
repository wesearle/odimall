const express = require('express');
const fs = require('fs');
const path = require('path');
const { createProxyMiddleware } = require('http-proxy-middleware');

const app = express();
const PORT = process.env.PORT || 8080;
const API_GATEWAY_URL = process.env.API_GATEWAY_URL || 'http://api-gateway:8081';
const RUM_ENABLED = String(process.env.RUM_ENABLED || '').toLowerCase() === 'true';
const RUM_PROXY_TARGET = process.env.RUM_PROXY_TARGET || 'http://127.0.0.1:9998';

const INDEX_PATH = path.join(__dirname, 'public', 'index.html');
let indexTemplate = null;

function loadIndexTemplate() {
  if (!indexTemplate) {
    indexTemplate = fs.readFileSync(INDEX_PATH, 'utf8');
  }
  return indexTemplate;
}

function buildRumInjection() {
  if (!RUM_ENABLED) {
    return '<script>window.__ODIMALL_RUM__={enabled:false};</script>';
  }
  const url = '/faro';
  return `<script>window.__ODIMALL_RUM__={enabled:true,url:${JSON.stringify(url)},version:"1.0.0"};</script><script src="/rum.bundle.js"></script>`;
}

app.use('/api', createProxyMiddleware({
  target: API_GATEWAY_URL,
  changeOrigin: true,
  pathRewrite: { '^/api': '' },
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

app.use(express.static(path.join(__dirname, 'public'), { index: false }));

app.get('*', (req, res) => {
  if (req.path.startsWith('/api') || req.path.startsWith('/faro')) {
    return res.status(404).send('Not found');
  }
  const html = loadIndexTemplate().replace(
    '<!-- ODIMALL_RUM_CONFIG -->',
    buildRumInjection()
  );
  res.type('html').send(html);
});

app.listen(PORT, () => {
  console.log(`Frontend server running on port ${PORT}`);
  console.log(`Proxying /api/* → ${API_GATEWAY_URL}`);
  if (RUM_ENABLED) {
    console.log(`RUM (Faro) enabled; proxying /faro/* → ${RUM_PROXY_TARGET}`);
  }
});
