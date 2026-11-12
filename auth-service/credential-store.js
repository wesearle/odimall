'use strict';

const db = require('./db');

// AUTH_SECURE_QUERIES=true switches the username lookup to a parameterized
// query (the "fixed" / remediated state). Default false keeps the intentionally
// injectable concatenated query so the breach is demonstrable out of the box.
//
// This is a DEMO application for Odigos trace-anomaly detection. The SQL
// injection below is deliberate and documented; it exists so a security
// product can learn what a credential-exfiltration trace looks like.
const SECURE_QUERIES = String(process.env.AUTH_SECURE_QUERIES || 'false').toLowerCase() === 'true';

const USER_COLUMNS = 'id, username, email, role, password_hash';

/**
 * CredentialStore — the single named module that reads the `users` table.
 *
 * Instrument CredentialStore.findByUsername with Odigos custom instrumentation:
 * the span's row count and db.statement are the signal. A normal login returns
 * 0 or 1 rows; an injected tautology returns the whole table.
 */
const CredentialStore = {
  /**
   * VULNERABLE lookup used by the login flow. The username is concatenated
   * directly into the SQL string. A normal username ("alice") yields one row;
   * a payload like  ' OR '1'='1  turns the WHERE clause into a tautology and
   * returns every credential row — the credential-dump breach.
   */
  async findByUsername(username) {
    if (SECURE_QUERIES) {
      return CredentialStore.findByUsernameSafe(username);
    }
    const sql =
      `SELECT ${USER_COLUMNS} FROM users WHERE username = '${username}' LIMIT 100`;
    const rows = await db.query(sql);
    return { rows, sql, matchedRows: rows.length };
  },

  /** Parameterized lookup — the remediated path (AUTH_SECURE_QUERIES=true). */
  async findByUsernameSafe(username) {
    const sql = `SELECT ${USER_COLUMNS} FROM users WHERE username = ? LIMIT 1`;
    const rows = await db.query(sql, [username]);
    return { rows, sql, matchedRows: rows.length };
  },

  /** Count of credential rows — used to flag an exfiltration (dump) in a span. */
  async countUsers() {
    const rows = await db.query('SELECT COUNT(*) AS n FROM users');
    return rows[0] ? rows[0].n : 0;
  },
};

module.exports = { CredentialStore, SECURE_QUERIES };
