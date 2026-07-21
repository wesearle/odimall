# OdiMall

A polyglot e-commerce microservices application built to showcase deep distributed tracing with [Odigos](https://odigos.io). Ten services, eight programming languages, MySQL, and Kafka — all observable without manual instrumentation.

## Services

| Service | Language | Framework | Port | Description |
|---------|----------|-----------|------|-------------|
| **frontend** | Node.js | Express | 8080 | Web UI and BFF proxy to API Gateway |
| **api-gateway** | Java 17 | Spring Boot 3.2 | 8081 | Request routing, custom HTTP headers |
| **product-service** | Python 3.11 | Flask + SQLAlchemy | 8082 | Product catalog with SQL commenter; optional **AI product blurb** (`POST /products/{id}/ai-summary`) — **demo**, **OpenAI**, or **Gemini** (`ODIMALL_AI_MODE`) for local copy or cloud LLM / GenAI-style traces |
| **cart-service** | Ruby 3.2 | Sinatra | 8083 | Shopping cart (in-memory) |
| **user-service** | Node.js 20 | Express | 8084 | Shipping info storage (in-memory) |
| **order-service** | Java 17 | Spring Boot 3.2 | 8085 | Order processing, calls all downstream services |
| **payment-service** | .NET 8 | ASP.NET Core | 8086 | Payment simulation |
| **shipping-service** | C++ | httplib (CMake) | 8087 | Shipping cost calculation |
| **inventory-service** | Go 1.21 | Gorilla Mux | 8088 | Inventory management, Kafka producer |
| **notification-service** | Go 1.21 | kafka-go | 8089 | Order notifications, Kafka consumer |
| **load-generator** | Go 1.21 | net/http | — | Continuous traffic generator (configurable interval) |

## Infrastructure

| Component | Image | Purpose |
|-----------|-------|---------|
| MySQL | `mysql:8.0` | Persistent datastore for products, orders, inventory (see [Ephemeral MySQL storage](#ephemeral-mysql-storage) to switch to `emptyDir`) |
| Kafka | `confluentinc/cp-kafka:7.5.0` | Event streaming (`order-events`, `order-events-dlq` topics) |
| Zookeeper | `confluentinc/cp-zookeeper:7.5.0` | Kafka coordination |

### Ephemeral MySQL storage

By default MySQL uses a **`PersistentVolumeClaim`** (`mysql-pvc`, 1Gi) so product, order, and inventory data survives pod restarts. For throwaway environments (kind/minikube, CI, quick demos) you can instead use ephemeral **`emptyDir`** storage — no `StorageClass` required, and the database is re-seeded from `mysql-init-configmap.yaml` on every pod restart.

**Helm** — set `mysql.persistence.enabled=false`:

```bash
helm upgrade --install odimall ./helm/odimall -n odimall --create-namespace \
  --set mysql.persistence.enabled=false
```

Related values (see `helm/odimall/values.yaml`):

| Value | Default | Description |
|-------|---------|-------------|
| `mysql.persistence.enabled` | `true` | `true` = PVC (persistent); `false` = `emptyDir` (ephemeral) |
| `mysql.persistence.size` | `1Gi` | PVC size when persistence is enabled |
| `mysql.persistence.storageClassName` | `""` | StorageClass for the PVC; empty uses the cluster default |

**Raw manifests** (`k8s/deploy.sh`) — pass `--ephemeral` (or set `MYSQL_EPHEMERAL=true`); the script rewrites the MySQL manifest to use `emptyDir` on the fly:

```bash
cd k8s
./deploy.sh --ephemeral
```

To edit `k8s/mysql.yaml` directly instead, delete the `PersistentVolumeClaim` block and swap the `mysql-data` volume to `emptyDir`:

```yaml
      volumes:
        - name: mysql-data
          emptyDir: {}
```

**Gotchas with ephemeral storage** — safe for demos, but every MySQL pod restart (including OOMKills — note the 512Mi limit — node evictions, and `rollout restart`) starts from a clean database:

- **Data resets**: orders vanish, inventory returns to its 10000 seed, and any manual SQL (e.g. adding product #11) is lost. The storefront still merges #11 client-side, but `GET /products/11` will 404 again.
- **Brief errors on restart**: services re-run `init.sql` each time, so expect a short burst of 500s until MySQL is ready (services auto-reconnect — no crash loops).
- **Stale references**: `cart-service`/`user-service` keep state in memory and aren't restarted with MySQL, so previously placed orders may 404 while the cart still shows items.

## Quick Start

### Prerequisites

- Kubernetes cluster (kind, minikube, EKS, GKE, etc.)
- `kubectl` configured to point at your cluster
- Container images available at `wsearle/odimall:*` (pre-built)

### Deploy OdiMall Without Grafana Faro (Browser SDK)

```bash
cd k8s
chmod +x deploy.sh
./deploy.sh
```

### Deploy OdiMall With Grafana Faro (Browser SDK)

**What gets enabled**

- **Frontend**: Grafana Faro Web SDK + tracing (`service.name` **`browser`** in Tempo). The UI loads `rum.bundle.js` only when `RUM_ENABLED=true`; beacons go to **`/faro`**, which the Node server proxies to Alloy.
- **Alloy**: chart deploys **`<helm-release-name>-rum-alloy`** (for example **`odimall-rum-alloy`** when the release is `odimall`) with `otelcol.receiver.faro` on port **9998** and forwards OTLP **gRPC** to **`rum.endpoint`**. 

**Prerequisites**

- An OTLP **gRPC** ingest reachable from the `odimall` namespace (host:port, no `http://` prefix), e.g. in-cluster LGTM **`lgtm.lgtm:4317`**.
- Set **`rum.otlpInsecure=false`** in Helm values only if that endpoint uses TLS with a verifiable cert (default is insecure gRPC for typical in-cluster setups).

**Install with Browser SDK Enabled**

```bash
helm upgrade --install odimall ./helm/odimall -n odimall --create-namespace \
  --set rumEnabled=true \
  --set rumEndpoint=lgtm.lgtm:4317
```

**Already deployed?**

Existing objects lack Helm ownership metadata. Either use a **fresh** `odimall` namespace, or on Helm 3.17+ / Helm 4:

```bash
helm upgrade --install odimall ./helm/odimall -n odimall --create-namespace \
  --set rum.enabled=true \
  --set rum.endpoint=lgtm.lgtm:4317 \
  --take-ownership
```

**Seeing data in Grafana**

- **Faro / browser** spans use **`service.name="browser"`**. They only appear when you use the **storefront in a real browser**; the **load generator** calls `api-gateway` directly and does **not** run Faro.
- Browser spans will automatically stitch to Odigos backend Traces

### Deploy with LLM Enabled

Enables "Ask AI" feature for products, it returns an explanation of each product on the product page.  Modes for both **Gemini** and **OpenAI** you will need to provide an AI token depending on the model you choose. 

Example for Gemini
```bash
helm upgrade --install odimall ./helm/odimall -n odimall --create-namespace \
  --set productService.aiMode=gemini \
  --set productService.gemini.enabled=true \
  --set productService.openai.enabled=false
```

Helm creates **`<release>-gemini-credentials`** with a placeholder. Put your real key in the Secret, then restart **`product-service`**:

```bash
kubectl patch secret odimall-gemini-credentials -n odimall --type merge \
  -p "{\"stringData\":{\"GEMINI_API_KEY\":\"$GEMINI_API_KEY\"}}"

kubectl rollout restart deployment/product-service -n odimall
```

### Access the UI

```bash
kubectl port-forward -n odimall svc/frontend 8080:8080
```

Open [http://localhost:8080](http://localhost:8080)

### Teardown

```bash
cd k8s
chmod +x destroy.sh
./destroy.sh
```

## Observability with Odigos

OdiMall is designed to produce rich, end-to-end distributed traces when instrumented with Odigos — no application code changes needed for basic tracing. The following features are built into the application to demonstrate advanced tracing capabilities.

### Trace Context Propagation through MySQL (SQL Commenter)

Three services propagate trace context into MySQL (product-service, order-service, and inventory-service) via [SQL commenter](https://google.github.io/sqlcommenter/), enabling Odigos to stitch MySQL server-side eBPF spans into the calling trace:

### Kafka Message Body in Spans

Both Go Kafka services use [`segmentio/kafka-go`](https://github.com/segmentio/kafka-go), which the Odigos Go eBPF agent instruments to capture `messaging.message.body` on both producer and consumer spans. This makes the full order event payload visible in traces.

### Custom Code Instrumentation

OdiMall includes several **Java and Go** entry points that are useful targets for Odigos **custom instrumentation** (extra spans / richer attributes on business logic). 

Define these signatures when configuring custom rules:

**Java — API Gateway Request Processor:**
- Class: `com.odimall.gateway.processor.OdiMallRequestProcessor`
- Method: `processRequest`

**Java — Order Service Order Processor:**
- Class: `com.odimall.order.processor.OrderProcessor`
- Method: `processOrder`

**Java — Order Service Retail fulfillment policy gate (Shadow Peak Mystery Crate Problem Pattern):**
- Class: `com.odimall.order.policy.RetailFulfillmentGate`
- Method: `assessPipelineCoherence`

**Go — Inventory Service Reserve Handler:**
- Package: `main`
- Function: `reserveHandler`

**Go — Notification Service Kayak Chaos Processor:**
- Package: `main`
- Function: `processKayakChaosMessage`
- Signature: `processKayakChaosMessage(ctx context.Context, event OrderEvent) error`
- Uses the **Odigos Go Auto SDK** (`go.opentelemetry.io/auto/sdk`) to enrich the eBPF custom span with `RecordError` / `SetStatus(Error)` — no global `TracerProvider`

**Go — Notification Service Normal Message Processor:**
- Package: `main`
- Function: `processNormalMessage`

**Python — Product Service Gemini blurb (`genai_tracing.py`):**
- Enriches the existing Odigos **`generate_content`** span (no extra client span) with:
  - `gen_ai.usage.thoughts_tokens` / `gen_ai.usage.reasoning.output_tokens`
  - `gen_ai.usage.total_tokens`
- Uses `trace.get_current_span().set_attribute()` only — **no** global `TracerProvider`

### LLM “AI blurb” (product-service)

The catalog exposes **`POST /products/{id}/ai-summary`** (via the gateway and **Storefront → product → “AI product blurb”**). Modes:

| Helm `productService.aiMode` | Behavior | Traces |
|------------------------------|----------|--------|
| **`demo`** (default) | Short blurb built **in-process** from DB fields (no network, **no API key**). | No external LLM client spans; HTTP **200**. |
| **`openai`** | **OpenAI Python SDK**; with Odigos + `opentelemetry-instrumentation-openai-v2` you get **GenAI** spans ([Odigos LLM observability](https://odigos.io/blog/llm-calls-are-the-new-blind-spot)). | Bad/placeholder keys → **401 → 502** until the key is valid. |
| **`gemini`** | **Google GenAI SDK** (`google-genai`) using **`GEMINI_API_KEY`** ([Google AI Studio](https://aistudio.google.com/apikey)). | Outbound HTTPS to Google; instrumentation depends on your Odigos / SDK setup. |

### Custom HTTP Headers

The API Gateway adds these headers to every proxied request (collect them with Odigos header collection):

| Header | Description |
|--------|-------------|
| `X-OdiMall-Request-Id` | Unique request identifier |
| `X-OdiMall-Correlation-Id` | Distributed correlation ID |
| `X-OdiMall-Timestamp` | Request timestamp (ISO 8601) |

## Purchase Flow

When a user places an order, the trace spans the following path:

```
Frontend (POST /api/orders)
  └─ API Gateway (POST /orders/**)
       └─ Order Service (POST /orders)
            ├─ Product Service (GET /products/{id})  →  MySQL SELECT (with SQL commenter)
            ├─ Payment Service (POST /payments/process)
            ├─ Shipping Service (POST /shipping/calculate)
            ├─ Inventory Service (POST /inventory/reserve)
            │    ├─ MySQL UPDATE (with SQL commenter)
            │    └─ Kafka produce → order-events
            │                         └─ Notification Service (consume)
            ├─ MySQL INSERT orders (with SQL commenter)
            └─ MySQL INSERT order_items (with SQL commenter)
```

## Problem Patterns

Several catalog items exist to demo tracing and policy behavior:

### Storm Chaser Tent 4P (Product #5) — Kafka latency

When purchased, the inventory service:
- Injects a **2-second delay** before producing the Kafka message
- Sets a `chaos: true` flag in the event payload
- Produces to the dead-letter queue (`order-events-dlq`)

The notification service:
- Detects the chaos flag and simulates **3 failing retry attempts** (logs only; span may stay OK)
- Each retry fails with escalating errors (connection timeout → 503 → max retries)
- Creates a **slow** Kafka consumer trace (enable Odigos **messaging payload** to see `"chaos": true`)

### Rapid River Kayak (Product #6) — Kafka notification ERROR (agent demo)

By default the load generator places random orders; **buying the kayak is normal** (no fault).

When **`KAYAK_FAULT_ENABLED=true`** on the load generator:

- **Every other** automated checkout is **kayak-only** (product #6)
- The order includes header **`X-OdiMall-Kayak-Chaos: true`** (gateway → order-service → inventory)
- **inventory-service** publishes Kafka JSON with **`"kayakChaos": true`**, then returns **HTTP 503**
- **order-service** returns **HTTP 503** (order is not persisted) — **ERROR spans on the checkout trace** (Odigos eBPF only; no app instrumentation)
- **notification-service** enriches the Odigos custom span on `processKayakChaosMessage` with **ERROR** status and the email-provider rejection message (Auto SDK)

**Enable** (frequent kayak faults):

```bash
kubectl set env -n odimall deployment/load-generator KAYAK_FAULT_ENABLED=true
kubectl rollout restart deployment/load-generator -n odimall
```

**Disable:**

```bash
kubectl set env -n odimall deployment/load-generator KAYAK_FAULT_ENABLED-
kubectl rollout restart deployment/load-generator -n odimall
```

Helm: `--set loadGenerator.kayakFaultEnabled=true`

**Agent workflow:** ERROR spans on **`inventory-service`** / **`order-service`** (`HTTP 503`) on the synchronous checkout trace → enable Odigos **messaging payload** on `notification-service` → reproduce → Kafka message body shows `"kayakChaos": true` and `productId: 6` → **`main.processKayakChaosMessage`** custom span shows **ERROR** with the email-provider message (Auto SDK enrichment).

Manual trigger (no load generator):

```bash
kubectl port-forward -n odimall svc/api-gateway 8081:8081
curl -sS -X POST http://localhost:8081/orders \
  -H 'Content-Type: application/json' \
  -H 'X-OdiMall-Kayak-Chaos: true' \
  -d '{"sessionId":"kayak-manual","items":[{"productId":6,"quantity":1,"price":499.99,"name":"Rapid River Kayak"}],"shipping":{"name":"Demo","address":"1 River Rd","city":"Denver","state":"CO","zip":"80202"}}'
```

### Glacier Sleeping Bag (Product #3) — MySQL DB Lock

When purchased, the order service:
- **Connection 1** acquires a `SELECT ... FOR UPDATE` lock on the inventory row
- **Connection 2** attempts to `UPDATE` the same row — blocks
- Connection 2 waits until the **10-second** `innodb_lock_wait_timeout` fires
- The lock timeout exception is caught; the order still completes
- Creates a visible lock contention pattern in database traces

### Shadow Peak Mystery Crate (Product #11) — policy denial (storefront-only SKU)

- Catalog item for **live UI demos** (same **Demo Chaos** badge treatment as other chaos SKUs). The **load generator excludes** this product ID so automated traffic never purchases it.
- When a **human** checks out with this item in the cart, **`RetailFulfillmentGate.assessPipelineCoherence`** runs a bounded CPU-heavy coherence probe, then denies the order before persistence; the API may respond with **409 Conflict** and a generic message.
- In an Odigos CPU profile, look for the long Java frame, then configure custom instrumentation with class **`com.odimall.order.policy.RetailFulfillmentGate`** and method **`assessPipelineCoherence`**.
- **Without** custom instrumentation on that method, logs and HTTP responses may not explain the denial; **with** instrumentation, inspect **arguments** and **`return.value`** (from `RetailPipelineAssessment.toString()`, containing the attestation) as described in [Enabling custom instrumentation for RetailFulfillmentGate (Shadow Peak demo)](#enabling-custom-instrumentation-for-retailfulfillmentgate-shadow-peak-demo).
- Tune the profile demo with **`SHADOW_PEAK_PROFILE_BURN_MS`** on `order-service` (default **2500 ms**; set **0** to disable the CPU burn).

## Products

| # | Name | Price | Category | Notes |
|---|------|-------|----------|-------|
| 1 | Trail Blazer Hiking Boots | $129.99 | Footwear | |
| 2 | Summit Backpack 65L | $189.99 | Packs | |
| 3 | Glacier Sleeping Bag | $149.99 | Sleep | DB lock demo |
| 4 | Alpine Trekking Poles | $79.99 | Accessories | |
| 5 | Storm Chaser Tent 4P | $299.99 | Shelter | Kafka chaos demo |
| 6 | Rapid River Kayak | $499.99 | Water | Kafka ERROR demo (`KAYAK_FAULT_ENABLED`) |
| 7 | Peak Performance Jacket | $219.99 | Apparel | |
| 8 | Wilderness First Aid Kit | $49.99 | Safety | |
| 9 | Canyon Explorer Headlamp | $39.99 | Lighting | |
| 10 | Mountain Stream Water Filter | $34.99 | Hydration | |
| 11 | Shadow Peak Mystery Crate | $59.99 | Limited | Storefront-only; policy / demo chaos |

### Adding product #11 to an existing MySQL database

The storefront **merges in** the Shadow Peak row on the client when `/products` succeeds but does not return id **11**, so the lab SKU is visible even before you migrate MySQL. For consistent catalog data, pricing in SQL, and `GET /products/11` from **product-service**, you should still add the row to the database.

`k8s/mysql-init-configmap.yaml` is only applied on **first** MySQL initialization. If your cluster already has a volume with the old seed data, run the following once against the **`odimall`** database (for example via `kubectl exec` into MySQL):

```sql
INSERT INTO products (id, name, description, price, image_url, category)
SELECT 11, 'Shadow Peak Mystery Crate',
  'Limited surprise crate for in-store demos. Checkout is restricted to the live storefront; automated buyers cannot purchase this SKU.',
  59.99, '/images/mystery-crate.png', 'Limited'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM products WHERE id = 11);

INSERT INTO inventory (product_id, quantity)
SELECT 11, 10000 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM inventory WHERE product_id = 11);
```

## Edge demos (EC2 VM chain + Windows .NET)

The storefront navbar includes optional buttons that call external hosts through the API gateway (trace headers and OdiMall correlation headers are forwarded):

| Button | Gateway route | Upstream | Config |
|--------|---------------|----------|--------|
| **VM chain** | `GET /vm-pipeline/run` | EC2 Linux VM `GET /chain` (C++ → Java → Postgres) | `VM_PIPELINE_ALPHA_URL` / Helm `apiGateway.vmPipelineAlphaUrl` |
| **Windows** | `GET /windows-pipeline/run` | Windows EC2 `GET /run` (.NET) | `WINDOWS_PIPELINE_BASE_URL` / Helm `apiGateway.windowsPipelineBaseUrl` |

**Windows install:** see [`windows-edge-service/README.md`](windows-edge-service/README.md) — run `install-windows.ps1` on a Windows EC2 instance, then set the gateway URL to `http://<public-ip>:9201`.

**VM install:** see [`vm-edge-services/README.md`](vm-edge-services/README.md).

## Project Structure

```
odimall/
├── api-gateway/          # Java/Spring Boot — request routing & custom headers
├── cart-service/          # Ruby/Sinatra — in-memory shopping cart
├── frontend/              # Node.js/Express — web UI & API proxy
├── inventory-service/     # Go — MySQL + Kafka producer
├── helm/odimall/            # Helm chart (optional Grafana Faro RUM + Alloy)
├── k8s/                   # Kubernetes manifests, deploy & destroy scripts
├── mysql-init/            # SQL schema & seed data
├── notification-service/  # Go — Kafka consumer
├── order-service/         # Java/Spring Boot — order processing & DB lock pattern
├── payment-service/       # .NET/ASP.NET Core — payment simulation
├── product-service/       # Python/Flask — product catalog with SQL commenter
├── shipping-service/      # C++ — shipping cost calculation
├── user-service/          # Node.js/Express — user/shipping info
├── vm-edge-services/      # EC2 Linux C++/Java/Postgres chain (VM navbar button)
├── windows-edge-service/  # EC2 Windows .NET edge service (Windows navbar button)
├── load-generator/        # Go — continuous traffic generator
├── build-push.sh          # Multi-arch image build & push script
└── README.md
```
