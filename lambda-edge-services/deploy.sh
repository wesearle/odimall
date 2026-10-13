#!/usr/bin/env bash
# Deploy OdiMall Lambda edge demos to AWS (us-east-2 by default).
# Cost posture: arm64, 128–256 MB, on-demand, Function URLs (no API Gateway),
# no provisioned concurrency. Invoked only by the UI button / manual URL hit —
# never by the load-generator.
set -euo pipefail

REGION="${AWS_REGION:-us-east-2}"
ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
ROLE_NAME="odimall-lambda-execution"
ROLE_ARN="arn:aws:iam::${ACCOUNT_ID}:role/${ROLE_NAME}"
ODIMALL_BASE_URL="${ODIMALL_BASE_URL:-http://k8s-odimall-odimallf-ef066ebb60-26217367.us-east-2.elb.amazonaws.com}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
BUILD="$ROOT/.build"
mkdir -p "$BUILD"

PYTHON_FN="odimall-lambda-python"
NODE_FN="odimall-lambda-nodejs"
JAVA_FN="odimall-lambda-java"

echo "=== OdiMall Lambda deploy ==="
echo "Account: $ACCOUNT_ID"
echo "Region:  $REGION"
echo "OdiMall: $ODIMALL_BASE_URL"
echo ""

# --- IAM role (shared) ---
if ! aws iam get-role --role-name "$ROLE_NAME" >/dev/null 2>&1; then
  echo "Creating IAM role $ROLE_NAME ..."
  TRUST='{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Principal": { "Service": "lambda.amazonaws.com" },
      "Action": "sts:AssumeRole"
    }]
  }'
  aws iam create-role \
    --role-name "$ROLE_NAME" \
    --assume-role-policy-document "$TRUST" \
    --description "OdiMall Lambda edge demos (Python/Node/Java)" >/dev/null
  aws iam attach-role-policy \
    --role-name "$ROLE_NAME" \
    --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole
  echo "Waiting for IAM role to propagate..."
  sleep 10
else
  echo "IAM role $ROLE_NAME already exists"
fi

ensure_function_url() {
  local fn="$1"
  local existing
  existing="$(aws lambda get-function-url-config --function-name "$fn" --region "$REGION" \
    --query FunctionUrl --output text 2>/dev/null || true)"
  if [[ -n "$existing" && "$existing" != "None" ]]; then
    echo "$existing"
    return
  fi
  aws lambda create-function-url-config \
    --function-name "$fn" \
    --region "$REGION" \
    --auth-type NONE \
    --cors '{"AllowOrigins":["*"],"AllowMethods":["GET"],"AllowHeaders":["*"]}' \
    --query FunctionUrl --output text
  # Public invoke permission for Function URL (AuthType NONE)
  aws lambda add-permission \
    --function-name "$fn" \
    --region "$REGION" \
    --statement-id FunctionURLAllowPublicAccess \
    --action lambda:InvokeFunctionUrl \
    --principal "*" \
    --function-url-auth-type NONE >/dev/null 2>&1 || true
}

upsert_function() {
  local fn="$1"
  local runtime="$2"
  local handler="$3"
  local zip="$4"
  local memory="$5"
  local env_json="$6"

  if aws lambda get-function --function-name "$fn" --region "$REGION" >/dev/null 2>&1; then
    echo "Updating code: $fn"
    aws lambda update-function-code \
      --function-name "$fn" \
      --region "$REGION" \
      --zip-file "fileb://$zip" >/dev/null
    aws lambda wait function-updated --function-name "$fn" --region "$REGION"
    echo "Updating config: $fn"
    aws lambda update-function-configuration \
      --function-name "$fn" \
      --region "$REGION" \
      --runtime "$runtime" \
      --handler "$handler" \
      --role "$ROLE_ARN" \
      --timeout 20 \
      --memory-size "$memory" \
      --architectures arm64 \
      --environment "$env_json" >/dev/null
    aws lambda wait function-updated --function-name "$fn" --region "$REGION"
  else
    echo "Creating function: $fn"
    aws lambda create-function \
      --function-name "$fn" \
      --region "$REGION" \
      --runtime "$runtime" \
      --handler "$handler" \
      --role "$ROLE_ARN" \
      --timeout 20 \
      --memory-size "$memory" \
      --architectures arm64 \
      --zip-file "fileb://$zip" \
      --environment "$env_json" >/dev/null
    aws lambda wait function-active --function-name "$fn" --region "$REGION"
  fi
}

# --- Package Java ---
echo ""
echo "--- Building Java Lambda ---"
(cd "$ROOT/java-fulfillment" && mvn -q -DskipTests package)
cp "$ROOT/java-fulfillment/target/odimall-lambda-java.jar" "$BUILD/java.zip"

# --- Package Python (include requests — covered by Odigos Python auto-instr) ---
echo "--- Packaging Python Lambda ---"
rm -rf "$BUILD/python-pkg" "$BUILD/python.zip"
mkdir -p "$BUILD/python-pkg"
cp "$ROOT/python-orchestrator/handler.py" "$BUILD/python-pkg/"
python3 -m pip install -q --target "$BUILD/python-pkg" "requests>=2.31,<3" >/dev/null
(cd "$BUILD/python-pkg" && zip -qr "$BUILD/python.zip" .)

# --- Package Node ---
echo "--- Packaging Node.js Lambda ---"
rm -f "$BUILD/nodejs.zip"
(cd "$ROOT/nodejs-catalog" && zip -q "$BUILD/nodejs.zip" index.js package.json)

# --- Deploy Java first (terminal hop) ---
echo ""
JAVA_ENV="Variables={ODIMALL_BASE_URL=${ODIMALL_BASE_URL},HTTP_TIMEOUT_SEC=12}"
upsert_function "$JAVA_FN" "java21" "com.odimall.lambda.Handler::handleRequest" "$BUILD/java.zip" "256" "$JAVA_ENV"
JAVA_URL="$(ensure_function_url "$JAVA_FN" | tr -d '\r')"
# strip trailing slash
JAVA_URL="${JAVA_URL%/}"
echo "Java Function URL: $JAVA_URL"

# --- Deploy Node ---
NODE_ENV="Variables={ODIMALL_BASE_URL=${ODIMALL_BASE_URL},JAVA_LAMBDA_URL=${JAVA_URL},PRODUCT_ID=1,HTTP_TIMEOUT_MS=12000}"
upsert_function "$NODE_FN" "nodejs20.x" "index.handler" "$BUILD/nodejs.zip" "128" "$NODE_ENV"
NODE_URL="$(ensure_function_url "$NODE_FN" | tr -d '\r')"
NODE_URL="${NODE_URL%/}"
echo "Node Function URL: $NODE_URL"

# --- Deploy Python (entry) ---
PY_ENV="Variables={ODIMALL_BASE_URL=${ODIMALL_BASE_URL},NODE_LAMBDA_URL=${NODE_URL},HTTP_TIMEOUT_SEC=12}"
upsert_function "$PYTHON_FN" "python3.12" "handler.handler" "$BUILD/python.zip" "128" "$PY_ENV"
PYTHON_URL="$(ensure_function_url "$PYTHON_FN" | tr -d '\r')"
PYTHON_URL="${PYTHON_URL%/}"
echo "Python Function URL: $PYTHON_URL"

# Persist URLs for helm / docs
cat > "$BUILD/function-urls.env" <<EOF
LAMBDA_PIPELINE_PYTHON_URL=${PYTHON_URL}
LAMBDA_PIPELINE_NODE_URL=${NODE_URL}
LAMBDA_PIPELINE_JAVA_URL=${JAVA_URL}
ODIMALL_BASE_URL=${ODIMALL_BASE_URL}
EOF

echo ""
echo "=== Deploy complete ==="
echo ""
echo "Entry URL (set as Helm apiGateway.lambdaPipelineUrl):"
echo "  $PYTHON_URL"
echo ""
echo "Smoke test:"
echo "  curl -sS '$PYTHON_URL' | python3 -m json.tool"
echo ""
echo "URLs written to: $BUILD/function-urls.env"
