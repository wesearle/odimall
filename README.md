# OdiMall

A polyglot e-commerce microservices application built to showcase deep distributed tracing with [Odigos](https://odigos.io). Ten services, seven programming languages, MySQL, and Kafka — all observable without manual instrumentation.

## Services

| Service | Language | Framework | Port | Description |
|---------|----------|-----------|------|-------------|
| **frontend** | Node.js | Express | 8080 | Web UI and BFF proxy to API Gateway |
| **api-gateway** | Java 17 | Spring Boot 3.2 | 8081 | Request routing, custom HTTP headers |
| **product-service** | Python 3.11 | Flask + SQLAlchemy | 8082 | Product catalog with SQL commenter |
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
| MySQL | `mysql:8.0` | Persistent datastore for products, orders, inventory |
| Kafka | `confluentinc/cp-kafka:7.5.0` | Event streaming (`order-events`, `order-events-dlq` topics) |
| Zookeeper | `confluentinc/cp-zookeeper:7.5.0` | Kafka coordination |

## Quick Start

### Prerequisites

- Kubernetes cluster (kind, minikube, EKS, GKE, etc.)
- `kubectl` configured to point at your cluster
- Container images available at `wsearle/odimall:*` (pre-built)

### Deploy without browser instrumentation

```bash
cd k8s
chmod +x deploy.sh
./deploy.sh
```

### Deploy OdiMall with Grafana Faro (Browser SDK)

**What gets enabled**

- **Frontend**: Grafana Faro Web SDK + tracing (`service.name` **`browser`** in Tempo). The UI loads `rum.bundle.js` only when `RUM_ENABLED=true`; beacons go to **`/faro`**, which the Node server proxies to Alloy.
- **Alloy**: chart deploys **`<helm-release-name>-rum-alloy`** (for example **`odimall-rum-alloy`** when the release is `odimall`) with `otelcol.receiver.faro` on port **9998** and forwards OTLP **gRPC** to **`rum.endpoint`**. Alloy runs with **`--stability.level=experimental`** (required for the Faro receiver).

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
- After changing the frontend image, **hard-refresh** the browser so `rum.bundle.js` updates.

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

**Go — Notification Service Normal Message Processor:**
- Package: `main`
- Function: `processNormalMessage`

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

### Storm Chaser Tent 4P (Product #5) — Kafka Issue

When purchased, the inventory service:
- Injects a **2-second delay** before producing the Kafka message
- Sets a `chaos: true` flag in the event payload
- Produces to the dead-letter queue (`order-events-dlq`)

The notification service:
- Detects the chaos flag and simulates **3 failing retry attempts**
- Each retry fails with escalating errors (connection timeout → 503 → max retries)
- Creates visible error spans in the Kafka consumer trace

### Glacier Sleeping Bag (Product #3) — MySQL DB Lock

When purchased, the order service:
- **Connection 1** acquires a `SELECT ... FOR UPDATE` lock on the inventory row
- **Connection 2** attempts to `UPDATE` the same row — blocks
- Connection 2 waits until the **5-second** `innodb_lock_wait_timeout` fires
- The lock timeout exception is caught; the order still completes
- Creates a visible lock contention pattern in database traces

### Shadow Peak Mystery Crate (Product #11) — policy denial (storefront-only SKU)

- Catalog item for **live UI demos** (same **Demo Chaos** badge treatment as other chaos SKUs). The **load generator excludes** this product ID so automated traffic never purchases it.
- When a **human** checks out with this item in the cart, **`RetailFulfillmentGate.assessPipelineCoherence`** can deny the order before persistence; the API may respond with **409 Conflict** and a generic message.
- **Without** custom instrumentation on that method, logs and HTTP responses may not explain the denial; **with** instrumentation, inspect **arguments** and **`return.value`** (from `RetailPipelineAssessment.toString()`, containing the attestation) as described in [Enabling custom instrumentation for RetailFulfillmentGate (Shadow Peak demo)](#enabling-custom-instrumentation-for-retailfulfillmentgate-shadow-peak-demo).

## Products

| # | Name | Price | Category | Notes |
|---|------|-------|----------|-------|
| 1 | Trail Blazer Hiking Boots | $129.99 | Footwear | |
| 2 | Summit Backpack 65L | $189.99 | Packs | |
| 3 | Glacier Sleeping Bag | $149.99 | Sleep | DB lock demo |
| 4 | Alpine Trekking Poles | $79.99 | Accessories | |
| 5 | Storm Chaser Tent 4P | $299.99 | Shelter | Kafka chaos demo |
| 6 | Rapid River Kayak | $499.99 | Water | |
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
  59.99, '/images/mystery-crate.svg', 'Limited'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM products WHERE id = 11);

INSERT INTO inventory (product_id, quantity)
SELECT 11, 10000 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM inventory WHERE product_id = 11);
```

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
├── load-generator/        # Go — continuous traffic generator
├── build-push.sh          # Multi-arch image build & push script
└── README.md
```
