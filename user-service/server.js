const express = require('express');

const app = express();
const PORT = process.env.PORT || 8084;

app.use(express.json());

app.use((req, res, next) => {
  console.log(`[user-service] ${req.method} ${req.path}`);
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
  console.log(`[user-service] Stored shipping info for session ${sessionId}`);

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
  console.log(`User service running on port ${PORT}`);
});
