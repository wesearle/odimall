# OdiMall Lambda edge demos

Three AWS Lambda functions that form a cross-runtime chain, invoked **only** by the
storefront **Lambda** navbar button (or by hitting a Function URL directly). The
load-generator never calls these paths.

| Hop | Function | Runtime | Role |
|-----|----------|---------|------|
| 1 (entry) | `odimall-lambda-python` | Python 3.12 | Orchestrator — GETs OdiMall `/api/products`, then calls Node |
| 2 | `odimall-lambda-nodejs` | Node.js 20 | Catalog — GETs `/api/products/1`, then calls Java |
| 3 | `odimall-lambda-java` | Java 21 | Fulfillment — GETs `/api/products`, returns summary |

```
UI "Lambda" button
  → GET /api/lambda-pipeline/run
  → api-gateway
  → Python Function URL
      → OdiMall ALB /api/products
      → Node Function URL
          → OdiMall ALB /api/products/1
          → Java Function URL
              → OdiMall ALB /api/products
```

## Cost posture

- **arm64** (Graviton), **128–256 MB**, **on-demand** (no provisioned concurrency)
- **Function URLs** only — no API Gateway
- **No load-generator traffic** — pay only when you click the button
- No VPC (Lambdas reach OdiMall via the public ALB)

## Deploy

Requires AWS CLI credentials for account `061717858829` and Maven (Java package).

```bash
cd lambda-edge-services
./deploy.sh
# optional overrides:
# AWS_REGION=us-east-2 ODIMALL_BASE_URL=http://your-alb ./deploy.sh
```

The script prints the Python Function URL. Wire it into the cluster:

```bash
helm upgrade odimall ./helm/odimall -n odimall --reuse-values \
  --set apiGateway.lambdaPipelineUrl="<PYTHON_FUNCTION_URL>"
```

Also rebuild/push `api-gateway` + `frontend` after pulling the UI/gateway code changes.

## Instrumentation

These functions intentionally ship **without** OpenTelemetry or Odigos in the
application code. If Odigos (or another agent) later attaches a Lambda layer /
`AWS_LAMBDA_EXEC_WRAPPER`, the Java hop includes `aws-lambda-java-events` so the
common Function URL wrapper can load successfully.

To keep them uninstrumented, exclude these function names from your Odigos Lambda
instrumentation (`odimall-lambda-python`, `odimall-lambda-nodejs`,
`odimall-lambda-java`).

## Manual invoke

```bash
source .build/function-urls.env
curl -sS "$LAMBDA_PIPELINE_PYTHON_URL" | python3 -m json.tool
```
