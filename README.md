# OdiMall

A polyglot e-commerce microservices application built to showcase deep distributed tracing with [Odigos](https://odigos.io). Ten services, seven programming languages, MySQL, and Kafka — all observable without manual instrumentation.

## Architecture

```
                         ┌──────────────────────────────┐
                         │    Frontend (Node.js :8080)   │
                         │   Adventure Gear E-Commerce   │
                         └──────────────┬───────────────┘
                                        │ /api/*
                         ┌──────────────▼───────────────┐
                         │  API Gateway (Java :8081)     │
                         │  Custom HTTP Headers          │
                         │  Request Processing           │
                         └──┬─────┬─────┬─────┬─────────┘
                            │     │     │     │
               ┌────────────┘     │     │     └────────────────┐
               ▼                  ▼     ▼                      ▼
        ┌─────────────┐   ┌──────────┐ ┌──────────┐   ┌──────────────┐
        │ Product Svc │   │ Cart Svc │ │ User Svc │   │ Order Svc    │
        │ Python/Flask│   │ Ruby     │ │ Node.js  │   │ Java/Spring  │
        │ :8082       │   │ :8083    │ │ :8084    │   │ :8085        │
        └──────┬──────┘   └──────────┘ └──────────┘   └─┬──┬──┬──┬──┘
               │                                         │  │  │  │
               │              ┌──────────────────────────┘  │  │  │
               │              │        ┌────────────────────┘  │  │
               ▼              ▼        ▼                       ▼  │
        ┌───────────┐  ┌──────────┐ ┌──────────┐  ┌──────────────┐│
        │           │  │ Payment  │ │ Shipping │  │ Inventory    ││
        │   MySQL   │◄─│ .NET     │ │ C++      │  │ Go :8088     ││
        │           │  │ :8086    │ │ :8087    │  └───────┬──────┘│
        │           │  └──────────┘ └──────────┘          │       │
        │           │◄────────────────────────────────────┘       │
        └───────────┘                                   Kafka     │
                                                    ┌─────▼──────┐│
                                                    │Notification││
                                                    │Go :8089    ││
                                                    └────────────┘│
                                                    ◄─────────────┘
```

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

### Deploy

```bash
cd k8s
chmod +x deploy.sh
./deploy.sh
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

## Building Images

Build and push multi-arch images (linux/amd64 + linux/arm64):

```bash
chmod +x build-push.sh
./build-push.sh
```

Images are pushed to `docker.io/wsearle/odimall:<service-name>`.

## Observability with Odigos

OdiMall is designed to produce rich, end-to-end distributed traces when instrumented with Odigos — no application code changes needed for basic tracing. The following features are built into the application to demonstrate advanced tracing capabilities.

### Trace Context Propagation through MySQL (SQL Commenter)

Three services propagate trace context into MySQL via [SQL commenter](https://google.github.io/sqlcommenter/), enabling Odigos to stitch MySQL server-side eBPF spans into the calling trace:

| Service | Implementation |
|---------|---------------|
| **product-service** (Python) | `google-cloud-sqlcommenter` with `with_opentelemetry=True` via SQLAlchemy |
| **order-service** (Java) | Manual `TraceContextHolder` that appends `/*traceparent='...'*/` from the HTTP header |
| **inventory-service** (Go) | Manual `sqlComment` helper that reads `Traceparent` from the HTTP header |

When working, a purchase trace includes MySQL server-side spans (`COM_QUERY`, `parse_sql`, `mysql_execute_command`, `ha_external_lock`) stitched directly under the calling service's SQL client spans.

### Kafka Message Body in Spans

Both Go Kafka services use [`segmentio/kafka-go`](https://github.com/segmentio/kafka-go), which the Odigos Go eBPF agent instruments to capture `messaging.message.body` on both producer and consumer spans. This makes the full order event payload visible in traces.

### Custom Java Code Instrumentation

Define these class/method signatures in Odigos for custom span generation:

**API Gateway — Request Processor:**
- Class: `com.odimall.gateway.processor.OdiMallRequestProcessor`
- Method: `processRequest(String requestId, String endpoint, String method)`

**Order Service — Order Processor:**
- Class: `com.odimall.order.processor.OrderProcessor`
- Method: `processOrder(String orderId, String sessionId, List items)`

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

Two products trigger deliberate issues visible in traces:

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

## Products

| # | Name | Price | Category | Chaos |
|---|------|-------|----------|-------|
| 1 | Trail Blazer Hiking Boots | $129.99 | Footwear | |
| 2 | Summit Backpack 65L | $189.99 | Packs | |
| 3 | Glacier Sleeping Bag | $149.99 | Sleep | DB Lock |
| 4 | Alpine Trekking Poles | $79.99 | Accessories | |
| 5 | Storm Chaser Tent 4P | $299.99 | Shelter | Kafka chaos |
| 6 | Rapid River Kayak | $499.99 | Water | |
| 7 | Peak Performance Jacket | $219.99 | Apparel | |
| 8 | Wilderness First Aid Kit | $49.99 | Safety | |
| 9 | Canyon Explorer Headlamp | $39.99 | Lighting | |
| 10 | Mountain Stream Water Filter | $34.99 | Hydration | |

## Project Structure

```
odimall/
├── api-gateway/          # Java/Spring Boot — request routing & custom headers
├── cart-service/          # Ruby/Sinatra — in-memory shopping cart
├── frontend/              # Node.js/Express — web UI & API proxy
├── inventory-service/     # Go — MySQL + Kafka producer
├── k8s/                   # Kubernetes manifests, deploy & destroy scripts
├── mysql-init/            # SQL schema & seed data
├── notification-service/  # Go — Kafka consumer
├── order-service/         # Java/Spring Boot — order processing & DB lock pattern
├── payment-service/       # .NET/ASP.NET Core — payment simulation
├── product-service/       # Python/Flask — product catalog with SQL commenter
├── shipping-service/      # C++ — shipping cost calculation
├── user-service/          # Node.js/Express — user/shipping info
├── build-push.sh          # Multi-arch image build & push script
└── README.md
```
