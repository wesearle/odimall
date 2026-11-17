const express = require('express');
const { writeSync } = require('fs');

// Stdout is not a TTY in Kubernetes; console.log can be fully buffered and
// kubectl logs appears empty until flush. writeSync(1, ...) is immediate.
function logLine(message) {
  try {
    writeSync(1, `[user-service] ${message}\n`);
  } catch {
    console.error(message);
  }
}

logLine(`boot pid=${process.pid} node=${process.version}`);

const app = express();
const PORT = process.env.PORT || 8084;

// --- Demo: intentionally lax upload handling -------------------------------
// By default Express caps JSON bodies at 100kb, which quietly blocks the
// oversized / compressed-upload ("zip bomb") attack. When LARGE_UPLOADS_ENABLED
// is true we raise the limit AND inflate gzip request bodies, so a tiny
// Content-Encoding: gzip payload can expand into a huge in-memory body. That
// inflated size is what shows up as a Payload-size anomaly in the trace.
// Set LARGE_UPLOADS_ENABLED=false (the safe default) to restore the 100kb cap.
const LARGE_UPLOADS = String(process.env.LARGE_UPLOADS_ENABLED || 'false') === 'true';
const UPLOAD_LIMIT = process.env.UPLOAD_LIMIT || '64mb';

// A verify hook lets us record the *decompressed* byte length of each request,
// so the log (and the span) reflect the inflated size, not the gzip wire size.
function captureSize(req, _res, buf) {
  req.rawByteLength = buf ? buf.length : 0;
}

if (LARGE_UPLOADS) {
  logLine(`LARGE_UPLOADS enabled (limit=${UPLOAD_LIMIT}, gzip inflate on) -- oversized-upload attack surface OPEN`);
  app.use(express.json({ limit: UPLOAD_LIMIT, inflate: true, verify: captureSize }));
} else {
  app.use(express.json({ verify: captureSize }));
}

app.use((req, res, next) => {
  logLine(`${req.method} ${req.path}`);
  next();
});

const shippingStore = new Map();

app.post('/users/shipping', (req, res) => {
  const { sessionId, name, address, city, state, zip } = req.body;

  if (!sessionId) {
    return res.status(400).json({ error: 'sessionId is required' });
  }

  // The decompressed body size is the anomaly signal: a normal shipping form is
  // a few hundred bytes; an oversized/zip-bomb upload inflates to many MB.
  const bytes = req.rawByteLength || 0;
  if (bytes > 1_000_000) {
    logLine(`OVERSIZED upload session=${sessionId} decompressedBytes=${bytes} (~${(bytes / 1048576).toFixed(1)}MB) -- possible zip-bomb / payload-size anomaly`);
  }

  const info = { name, address, city, state, zip, updatedAt: new Date().toISOString() };
  shippingStore.set(sessionId, info);
  logLine(`stored shipping for session ${sessionId} (${bytes} bytes)`);

  res.json({ success: true, receivedBytes: bytes });
});

app.get('/users/:sessionId/shipping', (req, res) => {
  const info = shippingStore.get(req.params.sessionId);
  if (!info) {
    return res.status(404).json({ error: 'Shipping info not found' });
  }
  res.json(info);
});

app.listen(PORT, () => {
  logLine(`listening on port ${PORT}`);
});
