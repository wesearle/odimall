'use strict';

const mysql = require('mysql2/promise');

const DB_HOST = process.env.DB_HOST || 'mysql';
const DB_PORT = parseInt(process.env.DB_PORT || '3306', 10);
const DB_USER = process.env.DB_USER || 'odimall';
const DB_PASSWORD = process.env.DB_PASSWORD || 'odimall123';
const DB_NAME = process.env.DB_NAME || 'odimall';

// Pool is lazily created so the service can boot before MySQL is ready
// (services re-run init.sql on restart; see README "Ephemeral MySQL storage").
let pool;

function getPool() {
  if (!pool) {
    pool = mysql.createPool({
      host: DB_HOST,
      port: DB_PORT,
      user: DB_USER,
      password: DB_PASSWORD,
      database: DB_NAME,
      waitForConnections: true,
      connectionLimit: 10,
      queueLimit: 0,
      // multipleStatements stays FALSE: the SQL-injection demo relies on a
      // single concatenated SELECT, not stacked statements. Leaving this off
      // keeps the breach realistic (data exfiltration via a tautology / UNION)
      // rather than arbitrary statement execution.
      multipleStatements: false,
    });
  }
  return pool;
}

/**
 * Raw query passthrough. The SQL string is built by the caller; this is the
 * single choke point where OdiMall's "db.statement" span attribute is set by
 * Odigos eBPF auto-instrumentation, so an injected tautology shows up verbatim
 * in the trace.
 */
async function query(sql, params) {
  const [rows] = await getPool().query(sql, params);
  return rows;
}

module.exports = { getPool, query };
