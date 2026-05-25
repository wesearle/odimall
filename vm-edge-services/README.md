# VM edge demo (EC2): C++ → Java → Java + PostgreSQL

Three **separate processes** on one Amazon Linux 2023 host, chained over HTTP:

| Service | Language | Port | Bind | Calls |
|---------|----------|------|------|--------|
| **alpha** | C++ (`cpp-httplib`) | 9101 | `0.0.0.0` | beta |
| **beta** | Java 17 (`HttpServer`) | 9102 | `127.0.0.1` | gamma |
| **gamma** | Java 17 + JDBC | 9103 | `127.0.0.1` | PostgreSQL `demo_ping` |

Path on every hop: **`GET /chain`**

W3C headers `traceparent`, `tracestate`, and `baggage` are forwarded so Odigos (cluster + VM agent) can correlate spans.

## Prerequisites on EC2

- **Outbound HTTPS** for the first `cmake` run (CMake `FetchContent` downloads [cpp-httplib](https://github.com/yhirose/cpp-httplib)).
- **Inbound TCP 9101** from your laptop (or test host) only, in the security group.
- **x86_64 or arm64** — build runs natively on the instance.

## One-shot install

```bash
scp -r vm-edge-services ec2-user@<PUBLIC_IP>:~
ssh ec2-user@<PUBLIC_IP>
chmod +x ~/vm-edge-services/install-amzn2023.sh ~/vm-edge-services/setup-postgres-amzn2023.sh
~/vm-edge-services/install-amzn2023.sh
```

This installs build deps, initializes **PostgreSQL 15**, creates database **`odimall_vm`** / user **`odimall_vm`**, builds **C++ alpha** and **Java** JARs, copies artifacts to `/opt/odimall-vm-edge`, writes `/etc/odimall-vm/*.env`, and enables **systemd** units.

Smoke test on the instance:

```bash
curl -sS http://127.0.0.1:9101/chain | head -c 400 && echo
```

## OdiMall (Kubernetes) wiring

1. Set the API gateway env **`VM_PIPELINE_ALPHA_URL`** to the VM entry base **only** (no path), e.g. `http://203.0.113.10:9101`.
2. **Helm:** `apiGateway.vmPipelineAlphaUrl: "http://YOUR_IP:9101"` in `values.yaml` (or `--set`).
3. **kind / plain YAML:** uncomment and fill `VM_PIPELINE_ALPHA_URL` in `k8s/api-gateway.yaml`, then apply.
4. Rebuild/push the **`api-gateway`** image if you use a remote registry.

The storefront navbar **“VM chain”** calls `GET /api/vm-pipeline/run` → gateway → `http://<VM>:9101/chain` → beta → gamma → Postgres.

If `VM_PIPELINE_ALPHA_URL` is unset, the gateway returns **503** with a JSON hint (expected for clusters that are not using the demo).

## Configuration files

| File | Purpose |
|------|---------|
| `/etc/odimall-vm/gamma.env` | `JDBC_URL=...` for gamma (chmod `0600`; contains DB password) |
| `/etc/odimall-vm/beta.env` | `GAMMA_BASE_URL` (default `http://127.0.0.1:9103`) |

Override DB password when installing:

```bash
export POSTGRES_ODIMALL_VM_PASSWORD='your-long-random-secret'
~/vm-edge-services/setup-postgres-amzn2023.sh
# then rewrite gamma.env JDBC_URL password to match before restarting gamma
```

If traces show **two Java processes both as `service.name: "java"`**, set **`OTEL_SERVICE_NAME`** on each unit (the shipped systemd files set **`vm-edge-beta`** and **`vm-edge-gamma`**). Copy updated units to `/etc/systemd/system/`, then **`sudo systemctl daemon-reload`** and **`sudo systemctl restart odimall-vm-beta odimall-vm-gamma`**.

## Logs & systemd

```bash
sudo journalctl -u odimall-vm-alpha -f
sudo systemctl status odimall-vm-gamma odimall-vm-beta odimall-vm-alpha
```

PostgreSQL unit name on AL2023 is usually **`postgresql`** (even when the RPM is `postgresql15-server`). The gamma unit’s `After=` lists `postgresql.service` and `postgresql15.service`; if your unit name differs, edit `systemd/odimall-vm-gamma.service` before install.

If **`setup-postgres-amzn2023.sh`** used to exit with “Could not find postgresql.service…”, that was almost always **`systemctl list-unit-files` going through a pager**, so `grep` saw no lines. The script now starts Postgres by trying **`systemctl enable --now postgresql`** (then fallbacks). Re-run **`~/vm-edge-services/setup-postgres-amzn2023.sh`** after updating the repo; if the DB was already initialized, the init step is skipped and only start + SQL grants run.

If you saw **`Data directory is not empty`** while re-running setup, the cause was checking for the data directory **without `sudo`**: `/var/lib/pgsql` is often not readable by `ec2-user`, so the script assumed the cluster was missing and tried **`initdb` again**. The script now uses **`sudo test -f .../PG_VERSION`** to detect an existing cluster.

If **`psql`** reported **Permission denied** on `sql/init_demo.sql` or **could not change directory to `/home/ec2-user/...`**, the **`postgres` OS user** cannot traverse `ec2-user`’s home (usually mode `700`). The setup script now **copies the SQL file into `/tmp`** and runs **`psql` with working directory `/tmp`** so `postgres` can read the file and avoid the chdir warning.

If the VM chain JSON shows **`Ident authentication failed for user "odimall_vm"`**, **`pg_hba.conf`** matched a **`ident`** (or similar) rule for TCP loopback before password auth. Current **`setup-postgres-amzn2023.sh`** **prepends** `scram-sha-256` lines for `odimall_vm` on **`127.0.0.1` / `::1`** and reloads Postgres. Re-run that script after updating the repo, then **`sudo systemctl restart odimall-vm-gamma`** (beta/alpha can stay up).

## Manual rebuild (dev)

```bash
cmake -S cpp-alpha -B cpp-alpha/build -DCMAKE_BUILD_TYPE=Release
cmake --build cpp-alpha/build -j"$(nproc)"
mvn -f java/pom.xml package -DskipTests
```

## Stack layout (why this shape)

- **C++ alpha** is the only internet-facing process; beta/gamma stay on localhost.
- **PostgreSQL** backs the terminal hop so you can demo DB queries alongside HTTP tracing.
