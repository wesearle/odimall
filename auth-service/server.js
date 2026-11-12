'use strict';

/**
 * OdiMall auth-service
 * --------------------
 * A deliberately-instrumentable authentication service for the OdiMall demo.
 * It is built to showcase Odigos trace-anomaly detection for a security product:
 *
 *   Phase 1 (credential theft): the login username lookup is intentionally
 *   vulnerable to SQL injection (see credential-store.js). A tautology payload
 *   turns a single-row lookup into a full-table dump of every credential — an
 *   anomalous db.statement and row count on the `users` SELECT span.
 *
 *   Phase 2 (stolen-credential login): a valid login that arrives from an
 *   anomalous origin (unfamiliar client IP / user-agent). The login succeeds,
 *   but the trace carries the anomalous context for the detector to flag.
 *
 * This is a demo. The vulnerability is the point. Do not copy this login flow
 * into a real service. Set AUTH_SECURE_QUERIES=true to run the remediated path.
 */

const express = require('express');
const bcrypt = require('bcryptjs');
const jwt = require('jsonwebtoken');
const { writeSync } = require('fs');

const { CredentialStore, SECURE_QUERIES } = require('./credential-store');
const db = require('./db');

const PORT = process.env.PORT || 8090;
const JWT_SECRET = process.env.JWT_SECRET || 'odimall-demo-signing-key';
const TOKEN_TTL = process.env.TOKEN_TTL || '2h';

// Stdout is not a TTY in Kubernetes; console.log can be fully buffered.
function logLine(message) {
  try {
    writeSync(1, `[auth-service] ${message}\n`);
  } catch {
    console.error(message);
  }
}

logLine(`boot pid=${process.pid} node=${process.version} secureQueries=${SECURE_QUERIES}`);

/**
 * AnomalyContext — derived per request from headers the gateway forwards.
 * A "known good" login arrives without these markers; the attacker simulation
 * sets X-Forwarded-For / User-Agent to an unfamiliar origin.
 */
function anomalyContext(req) {
  const xff = (req.headers['x-forwarded-for'] || '').split(',')[0].trim();
  const clientIp = xff || req.socket.remoteAddress || 'unknown';
  const userAgent = req.headers['user-agent'] || 'unknown';
  const anomalyFlag = String(req.headers['x-odimall-origin'] || '').toLowerCase() === 'anomalous';
  return { clientIp, userAgent, anomalous: anomalyFlag };
}

/**
 * AuthService.authenticate — instrument this with Odigos custom instrumentation.
 * The return shape distinguishes a normal login, a SQL-injection breach (the
 * lookup returned the whole table), and a failed login.
 */
async function authenticate(username, password, ctx) {
  const lookup = await CredentialStore.findByUsername(username);
  const { rows, sql, matchedRows } = lookup;

  // Breach signal: a username lookup should never match more than one row.
  // When it does, an injected tautology dumped the credential table.
  const breach = matchedRows > 1;

  if (rows.length === 0) {
    logLine(`login FAILED user=${JSON.stringify(username)} reason=no_match ip=${ctx.clientIp}`);
    return { ok: false, status: 401, reason: 'invalid_credentials' };
  }

  // SQL-injection auth bypass: with a tautology the attacker never supplied a
  // valid password, yet the lookup returns rows. The naive service trusts the
  // first row and also leaks the exfiltrated account list.
  if (breach) {
    const exfiltrated = rows.map((r) => ({
      username: r.username,
      role: r.role,
      email: r.email,
      passwordHash: r.password_hash, // leaked credential material (bcrypt) — the breach
    }));
    const victim = rows[0];
    logLine(
      `SQLI BREACH rows=${matchedRows} exfiltrated_users=${exfiltrated.length} ` +
      `bypass_as=${victim.username} role=${victim.role} ip=${ctx.clientIp} ua=${JSON.stringify(ctx.userAgent)} ` +
      `statement=${JSON.stringify(sql)}`
    );
    const token = signToken(victim);
    return {
      ok: true,
      status: 200,
      breach: true,
      rowsReturned: matchedRows,
      user: publicUser(victim),
      exfiltrated,
      token,
    };
  }

  // Normal single-row path: verify the password hash.
  const user = rows[0];
  const valid = await bcrypt.compare(String(password || ''), user.password_hash || '');
  if (!valid) {
    logLine(`login FAILED user=${JSON.stringify(username)} reason=bad_password ip=${ctx.clientIp}`);
    return { ok: false, status: 401, reason: 'invalid_credentials' };
  }

  const anomalyNote = ctx.anomalous ? ' ORIGIN=anomalous (possible stolen credential)' : '';
  logLine(
    `login OK user=${user.username} role=${user.role} ip=${ctx.clientIp} ` +
    `ua=${JSON.stringify(ctx.userAgent)}${anomalyNote}`
  );
  return {
    ok: true,
    status: 200,
    breach: false,
    anomalousOrigin: ctx.anomalous,
    user: publicUser(user),
    token: signToken(user),
  };
}

function signToken(user) {
  return jwt.sign(
    { sub: user.id, username: user.username, role: user.role },
    JWT_SECRET,
    { expiresIn: TOKEN_TTL }
  );
}

function publicUser(user) {
  return { id: user.id, username: user.username, email: user.email, role: user.role };
}

const app = express();
app.use(express.json());
app.disable('x-powered-by');

app.use((req, res, next) => {
  logLine(`${req.method} ${req.path}`);
  next();
});

app.get('/auth/health', async (req, res) => {
  try {
    const n = await CredentialStore.countUsers();
    res.json({ status: 'ok', users: n, secureQueries: SECURE_QUERIES });
  } catch (err) {
    res.status(503).json({ status: 'degraded', error: err.message });
  }
});

app.post('/auth/login', async (req, res) => {
  const { username, password } = req.body || {};
  if (username === undefined || username === null) {
    return res.status(400).json({ error: 'username is required' });
  }
  const ctx = anomalyContext(req);
  try {
    const result = await authenticate(username, password, ctx);
    if (!result.ok) {
      return res.status(result.status).json({ error: 'Invalid username or password' });
    }
    const payload = {
      token: result.token,
      user: result.user,
    };
    // In breach mode the naive service leaks what the attacker exfiltrated.
    if (result.breach) {
      payload.warning = 'multiple credential rows returned for a single username lookup';
      payload.rowsReturned = result.rowsReturned;
      payload.exfiltrated = result.exfiltrated;
    }
    if (result.anomalousOrigin) {
      payload.anomalousOrigin = true;
    }
    return res.json(payload);
  } catch (err) {
    logLine(`login ERROR user=${JSON.stringify(username)} err=${err.message}`);
    return res.status(500).json({ error: 'authentication failed' });
  }
});

app.get('/auth/me', (req, res) => {
  const header = req.headers.authorization || '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : null;
  if (!token) {
    return res.status(401).json({ error: 'missing token' });
  }
  try {
    const claims = jwt.verify(token, JWT_SECRET);
    res.json({ user: { id: claims.sub, username: claims.username, role: claims.role } });
  } catch {
    res.status(401).json({ error: 'invalid token' });
  }
});

app.listen(PORT, () => {
  logLine(`listening on port ${PORT}`);
  if (!SECURE_QUERIES) {
    logLine('WARNING: running with INTENTIONALLY VULNERABLE queries (SQL-injection demo). ' +
      'Set AUTH_SECURE_QUERIES=true for the remediated path.');
  }
});

module.exports = { authenticate, anomalyContext };
