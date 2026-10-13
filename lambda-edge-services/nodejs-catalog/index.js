'use strict';

/**
 * OdiMall Lambda catalog hop (Node.js).
 *
 * No manual OTEL. Propagation is left to the Odigos Lambda layer:
 *   - inbound: aws-lambda instrumentation extracts Function URL W3C context
 *   - outbound: Node `http`/`https` (not undici fetch) is covered by
 *     @opentelemetry/instrumentation-http
 */

const http = require('http');
const https = require('https');
const { URL } = require('url');

const ODIMALL_BASE_URL = (
  process.env.ODIMALL_BASE_URL ||
  'http://k8s-odimall-odimallf-ef066ebb60-26217367.us-east-2.elb.amazonaws.com'
).replace(/\/$/, '');
const JAVA_LAMBDA_URL = (process.env.JAVA_LAMBDA_URL || '').replace(/\/$/, '');
const HTTP_TIMEOUT_MS = Number(process.env.HTTP_TIMEOUT_MS || 12000);
const PRODUCT_ID = process.env.PRODUCT_ID || '1';

const ODIMALL_HEADERS = {
  'x-odimall-request-id': 'X-OdiMall-Request-Id',
  'x-odimall-correlation-id': 'X-OdiMall-Correlation-Id',
  'x-odimall-timestamp': 'X-OdiMall-Timestamp',
};

function eventHeaders(event) {
  const incoming = (event && event.headers) || {};
  const lower = {};
  for (const [k, v] of Object.entries(incoming)) {
    if (v != null) lower[String(k).toLowerCase()] = String(v);
  }
  return lower;
}

function baseHeaders(lower) {
  const out = {
    Accept: 'application/json',
    'User-Agent': 'odimall-lambda-nodejs/1.0',
  };
  for (const [low, canon] of Object.entries(ODIMALL_HEADERS)) {
    if (lower[low]) out[canon] = lower[low];
  }
  return out;
}

function httpGetJson(urlString, headers) {
  return new Promise((resolve) => {
    let settled = false;
    const finish = (value) => {
      if (!settled) {
        settled = true;
        resolve(value);
      }
    };

    let url;
    try {
      url = new URL(urlString);
    } catch (err) {
      finish({
        ok: false,
        status: 0,
        body: { error: err.message || String(err), type: 'URLError' },
      });
      return;
    }

    const lib = url.protocol === 'https:' ? https : http;
    const req = lib.request(
      {
        protocol: url.protocol,
        hostname: url.hostname,
        port: url.port || (url.protocol === 'https:' ? 443 : 80),
        path: `${url.pathname}${url.search}`,
        method: 'GET',
        headers,
        timeout: HTTP_TIMEOUT_MS,
      },
      (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf8');
          let body;
          try {
            body = text ? JSON.parse(text) : {};
          } catch {
            body = { raw: text };
          }
          const status = res.statusCode || 0;
          finish({ ok: status >= 200 && status < 300, status, body });
        });
      }
    );

    req.on('timeout', () => {
      req.destroy(new Error('timeout'));
    });
    req.on('error', (err) => {
      finish({
        ok: false,
        status: 0,
        body: { error: err.message || String(err), type: err.name || 'Error' },
      });
    });
    req.end();
  });
}

exports.handler = async (event, context) => {
  const headers = baseHeaders(eventHeaders(event));
  const started = Date.now();

  const product = await httpGetJson(
    `${ODIMALL_BASE_URL}/api/products/${PRODUCT_ID}`,
    headers
  );

  let javaHop;
  if (!JAVA_LAMBDA_URL) {
    javaHop = { ok: false, status: 0, body: { error: 'JAVA_LAMBDA_URL not configured' } };
  } else {
    javaHop = await httpGetJson(JAVA_LAMBDA_URL, headers);
  }

  const ok = Boolean(product.ok) && Boolean(javaHop.ok);
  const productName =
    product.body && typeof product.body === 'object' ? product.body.name || null : null;

  const payload = {
    service: 'odimall-lambda-nodejs',
    runtime: 'nodejs20.x',
    role: 'catalog',
    ok,
    elapsedMs: Date.now() - started,
    odimall: {
      baseUrl: ODIMALL_BASE_URL,
      path: `/api/products/${PRODUCT_ID}`,
      status: product.status,
      productId: Number(PRODUCT_ID),
      productName,
    },
    next: javaHop.body && typeof javaHop.body === 'object' ? javaHop.body : javaHop,
    aws: {
      functionName: context && context.functionName,
      requestId: context && context.awsRequestId,
      memoryLimitMB: context && context.memoryLimitInMB,
    },
  };

  return {
    statusCode: ok ? 200 : 502,
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' },
    body: JSON.stringify(payload),
  };
};
