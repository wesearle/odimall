#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== OdiMall Kubernetes Deployment ==="
echo ""

echo "[1/5] Creating namespace..."
kubectl apply -f namespace.yaml

echo "[2/5] Applying MySQL init ConfigMap..."
kubectl apply -f mysql-init-configmap.yaml

echo "[3/5] Deploying MySQL..."
kubectl apply -f mysql.yaml
echo "      Waiting for MySQL to be ready..."
kubectl wait --namespace odimall \
  --for=condition=ready pod \
  --selector=app=mysql \
  --timeout=120s

echo "[4/5] Deploying Kafka (Zookeeper + Kafka + topic init)..."
kubectl apply -f kafka.yaml
echo "      Waiting for Zookeeper to be ready..."
kubectl wait --namespace odimall \
  --for=condition=ready pod \
  --selector=app=zookeeper \
  --timeout=90s
echo "      Waiting for Kafka to be ready..."
kubectl wait --namespace odimall \
  --for=condition=ready pod \
  --selector=app=kafka \
  --timeout=90s

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
  kubectl wait --namespace odimall \
    --for=condition=ready pod \
    --selector=app="$svc" \
    --timeout=120s
done

echo "[6/6] Deploying load generator..."
kubectl apply -f load-generator.yaml
echo "  Waiting for load-generator..."
kubectl wait --namespace odimall \
  --for=condition=ready pod \
  --selector=app=load-generator \
  --timeout=120s

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
