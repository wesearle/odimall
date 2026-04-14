#!/usr/bin/env bash
set -euo pipefail

echo "=== OdiMall Teardown ==="
echo ""
echo "This will delete the entire 'odimall' namespace and all resources within it."
read -p "Are you sure? (y/N) " confirm
if [[ "$confirm" != "y" && "$confirm" != "Y" ]]; then
  echo "Aborted."
  exit 0
fi

echo ""
echo "Deleting namespace 'odimall'..."
kubectl delete namespace odimall --timeout=120s

echo ""
echo "=== OdiMall fully removed ==="
