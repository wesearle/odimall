#!/bin/bash
set -e

REGISTRY="wsearle/odimall"
SERVICES=(frontend api-gateway product-service cart-service user-service order-service payment-service shipping-service inventory-service notification-service)

echo "=== OdiMall Docker Build & Push ==="
echo "Registry: $REGISTRY"
echo ""

docker buildx create --name odimall-builder --use 2>/dev/null || docker buildx use odimall-builder 2>/dev/null || true

for svc in "${SERVICES[@]}"; do
    echo "--- Building and pushing $svc ---"
    docker buildx build \
        --platform linux/amd64,linux/arm64 \
        -t "$REGISTRY:$svc" \
        --push \
        "$svc/"
    echo "✓ $svc pushed to $REGISTRY:$svc"
    echo ""
done

echo "=== All images pushed ==="
echo ""
for svc in "${SERVICES[@]}"; do
    echo "  $REGISTRY:$svc"
done
