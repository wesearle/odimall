#!/usr/bin/env bash
# Initialize PostgreSQL (Amazon Linux 2023), create odimall_vm role + DB, apply demo schema.
# Requires: sudo, postgresql15-server (or compatible) installed.
# Optional: export POSTGRES_ODIMALL_VM_PASSWORD='your-secret' (default matches gamma JDBC default).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL_FILE="${ROOT}/sql/init_demo.sql"
PGPASS="${POSTGRES_ODIMALL_VM_PASSWORD:-odimall_vm_demo}"

if [[ ! -f "${SQL_FILE}" ]]; then
  echo "Missing ${SQL_FILE}" >&2
  exit 1
fi

start_postgres() {
  # list-unit-files can be paged or column-aligned; prefer enable --now and unit files on disk.
  if sudo systemctl enable --now postgresql 2>/dev/null; then
    echo "Started systemd unit: postgresql"
    return
  fi
  if sudo systemctl enable --now postgresql15 2>/dev/null; then
    echo "Started systemd unit: postgresql15"
    return
  fi
  local f u
  for f in /usr/lib/systemd/system/postgresql*.service /lib/systemd/system/postgresql*.service; do
    [[ -e "$f" ]] || continue
    u="$(basename "$f" .service)"
    # Skip template units (postgresql@.service); they need an instance name.
    [[ "$u" != *@ ]] || continue
    if sudo systemctl enable --now "$u" 2>/dev/null; then
      echo "Started systemd unit: $u (from $f)"
      return
    fi
  done
  echo "Could not start PostgreSQL. Tried: postgresql, postgresql15, and postgresql*.service under /usr/lib/systemd/system/." >&2
  echo "Diagnostics (run and share output if you need help):" >&2
  echo "  ls -la /usr/lib/systemd/system/postgresql*.service 2>&1" >&2
  echo "  systemctl list-unit-files --no-pager --type=service 2>&1 | grep -i postgres || true" >&2
  exit 1
}

# Must use sudo for checks: /var/lib/pgsql is often not traversable by ec2-user, so [[ -d ... ]] lies
# and postgresql-setup --initdb runs again → "Data directory is not empty".
cluster_initialized() {
  sudo test -f /var/lib/pgsql/data/PG_VERSION 2>/dev/null \
    || sudo test -f /var/lib/pgsql15/data/PG_VERSION 2>/dev/null
}

if ! cluster_initialized; then
  if command -v postgresql-setup &>/dev/null; then
    sudo postgresql-setup --initdb
  elif command -v /usr/bin/postgresql-setup &>/dev/null; then
    sudo /usr/bin/postgresql-setup --initdb
  else
    echo "postgresql-setup not found; install postgresql15-server." >&2
    exit 1
  fi
else
  echo "PostgreSQL data directory already initialized; skipping initdb."
fi

start_postgres

# Default AL/RHEL pg_hba often uses "ident" for host 127.0.0.1 — JDBC password auth then fails with
# "Ident authentication failed for user odimall_vm". Prepend explicit scram-sha-256 rules (first match wins).
ensure_pg_hba_odimall_vm_loopback() {
  local hba=""
  hba="$(cd /tmp && sudo -u postgres psql -tAXc 'SHOW hba_file;' 2>/dev/null | head -1 | tr -d '\r')"
  hba="${hba#"${hba%%[![:space:]]*}"}"
  hba="${hba%"${hba##*[![:space:]]}"}"
  if [[ -z "$hba" ]] || ! sudo test -f "$hba"; then
    for candidate in /var/lib/pgsql/data/pg_hba.conf /var/lib/pgsql15/data/pg_hba.conf; do
      if sudo test -f "$candidate"; then
        hba="$candidate"
        break
      fi
    done
  fi
  if ! sudo test -f "$hba"; then
    echo "Could not locate pg_hba.conf; skip hba patch." >&2
    return 0
  fi

  if sudo grep -qF 'odimall vm-edge hba' "$hba" 2>/dev/null; then
    echo "pg_hba.conf already allows password auth for odimall_vm on loopback."
    return 0
  fi

  local merged
  merged="$(mktemp)"
  {
    echo '# odimall vm-edge hba (password/JDBC on loopback — inserted by setup-postgres-amzn2023.sh)'
    echo 'host    odimall_vm    odimall_vm    127.0.0.1/32    scram-sha-256'
    echo 'host    odimall_vm    odimall_vm    ::1/128         scram-sha-256'
    echo ''
    sudo cat "$hba"
  } > "$merged"
  sudo install -m 600 -o postgres -g postgres "$merged" "$hba"
  rm -f "$merged"
  echo "Reloading PostgreSQL after pg_hba.conf update..."
  sudo systemctl reload postgresql 2>/dev/null || sudo systemctl reload postgresql15 2>/dev/null || true
}

ensure_pg_hba_odimall_vm_loopback

# postgres cannot chdir into ec2-user's $HOME (mode 700) or read paths there; run psql from /tmp
# and apply schema from a world-readable copy under /tmp.
stage_sql="$(mktemp /tmp/odimall_vm_init_demo.XXXXXX.sql)"
cp "${SQL_FILE}" "${stage_sql}"
chmod 644 "${stage_sql}"
trap 'rm -f "${stage_sql}"' EXIT

psql_as_postgres() {
  (cd /tmp && sudo -u postgres psql "$@")
}

if ! psql_as_postgres -tc "SELECT 1 FROM pg_roles WHERE rolname='odimall_vm'" | grep -q 1; then
  psql_as_postgres -v ON_ERROR_STOP=1 -c "CREATE USER odimall_vm WITH PASSWORD '${PGPASS}';"
fi

if ! psql_as_postgres -tc "SELECT 1 FROM pg_database WHERE datname='odimall_vm'" | grep -q 1; then
  psql_as_postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE odimall_vm OWNER odimall_vm;"
fi

psql_as_postgres -v ON_ERROR_STOP=1 -d odimall_vm -f "${stage_sql}"
psql_as_postgres -v ON_ERROR_STOP=1 -d odimall_vm -c "GRANT ALL ON SCHEMA public TO odimall_vm;"
psql_as_postgres -v ON_ERROR_STOP=1 -d odimall_vm -c "GRANT ALL ON ALL TABLES IN SCHEMA public TO odimall_vm;"
psql_as_postgres -v ON_ERROR_STOP=1 -d odimall_vm -c "GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO odimall_vm;"

echo "PostgreSQL ready: database odimall_vm, user odimall_vm (password from POSTGRES_ODIMALL_VM_PASSWORD or default)."
