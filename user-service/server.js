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

app.use(express.json());

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

  const info = { name, address, city, state, zip, updatedAt: new Date().toISOString() };
  shippingStore.set(sessionId, info);
  logLine(`stored shipping for session ${sessionId}`);

  res.json({ success: true });
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
