#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# MySQL storage mode. Persistent (PVC) by default; pass --ephemeral (or set
# MYSQL_EPHEMERAL=true) to use ephemeral emptyDir storage instead.
MYSQL_EPHEMERAL="${MYSQL_EPHEMERAL:-false}"
for arg in "$@"; do
  case "$arg" in
    --ephemeral) MYSQL_EPHEMERAL=true ;;
    --persistent) MYSQL_EPHEMERAL=false ;;
    -h|--help)
      echo "Usage: $0 [--ephemeral|--persistent]"
      echo "  --ephemeral   Use emptyDir for MySQL (data lost on pod restart)"
      echo "  --persistent  Use a PersistentVolumeClaim for MySQL (default)"
      exit 0
      ;;
    *) echo "Unknown option: $arg" >&2; exit 1 ;;
  esac
done

wait_for_deployment() {
  local name=$1
  local timeout=${2:-120s}
  kubectl rollout status "deployment/$name" -n odimall --timeout="$timeout"
}

# Emit the MySQL manifest, converting the PVC to an emptyDir volume when
# ephemeral storage is requested (drops the PVC document, swaps the volume).
render_mysql_manifest() {
  if [ "$MYSQL_EPHEMERAL" = "true" ]; then
    sed -e '1,/^---$/d' \
        -e 's/^          persistentVolumeClaim:$/          emptyDir: {}/' \
        -e '/^            claimName: mysql-pvc$/d' \
        mysql.yaml
  else
    cat mysql.yaml
  fi
}

echo "=== OdiMall Kubernetes Deployment ==="
echo ""

echo "[1/5] Creating namespace..."
kubectl apply -f namespace.yaml

echo "[2/5] Applying MySQL init ConfigMap..."
kubectl apply -f mysql-init-configmap.yaml

if [ "$MYSQL_EPHEMERAL" = "true" ]; then
  echo "[3/5] Deploying MySQL (ephemeral emptyDir storage)..."
  echo "      NOTE: every MySQL pod restart (OOMKill, eviction, rollout) starts"
  echo "      from a clean DB: orders vanish, inventory resets to its 10000 seed,"
  echo "      and manual SQL (e.g. product #11) is lost. Expect a brief burst of"
  echo "      500s while init.sql re-runs (services auto-reconnect, no crash loops)."
else
  echo "[3/5] Deploying MySQL (persistent PVC storage)..."
fi
render_mysql_manifest | kubectl apply -f -
echo "      Waiting for MySQL to be ready..."
wait_for_deployment mysql 120s

echo "[4/5] Deploying Kafka (Zookeeper + Kafka + topic init)..."
kubectl apply -f kafka.yaml
echo "      Waiting for Zookeeper to be ready..."
wait_for_deployment zookeeper 90s
echo "      Waiting for Kafka to be ready..."
wait_for_deployment kafka 90s

echo "[5/6] Deploying microservices..."
kubectl apply \
  -f frontend.yaml \
  -f api-gateway.yaml \
  -f product-service.yaml \
  -f cart-service.yaml \
  -f user-service.yaml \
  -f order-service.yaml \
  -f payment-service.yaml \
  -f shipping-service.yaml \
  -f inventory-service.yaml \
  -f notification-service.yaml

echo ""
echo "Waiting for all microservice pods to be ready..."
for svc in frontend api-gateway product-service cart-service user-service \
           order-service payment-service shipping-service inventory-service \
           notification-service; do
  echo "  Waiting for $svc..."
  wait_for_deployment "$svc" 120s
done

echo "[6/6] Deploying load generator..."
kubectl apply -f load-generator.yaml
echo "  Waiting for load-generator..."
wait_for_deployment load-generator 120s

echo ""
echo "=== Deployment complete! ==="
echo ""
kubectl get pods -n odimall
echo ""
echo "To access the frontend:"
echo "  kubectl port-forward -n odimall svc/frontend 8080:8080"
echo "  Then open http://localhost:8080"
echo ""
echo "Load generator is running (default: every 5s)."
echo "  To change frequency: kubectl set env -n odimall deployment/load-generator INTERVAL=30s"
echo "  Kayak Kafka ERROR demo (every other order = Rapid River Kayak #6):"
echo "    kubectl set env -n odimall deployment/load-generator KAYAK_FAULT_ENABLED=true"
echo "    kubectl rollout restart deployment/load-generator -n odimall"
echo "  Disable kayak faults:"
echo "    kubectl set env -n odimall deployment/load-generator KAYAK_FAULT_ENABLED-"
echo "    kubectl rollout restart deployment/load-generator -n odimall"
