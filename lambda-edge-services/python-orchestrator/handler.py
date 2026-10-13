"""
OdiMall Lambda orchestrator (Python) — entry hop for the Lambda edge demo.

No manual OTEL. Propagation is left to the Odigos Lambda layer:
  - inbound: aws-lambda instrumentation extracts Function URL W3C context
  - outbound: `requests` is typically auto-instrumented (unlike stdlib urllib)
"""

from __future__ import annotations

import json
import os
import time
from typing import Any

import requests

ODIMALL_BASE_URL = os.environ.get(
    "ODIMALL_BASE_URL",
    "http://k8s-odimall-odimallf-ef066ebb60-26217367.us-east-2.elb.amazonaws.com",
).rstrip("/")
NODE_LAMBDA_URL = os.environ.get("NODE_LAMBDA_URL", "").rstrip("/")
HTTP_TIMEOUT_SEC = float(os.environ.get("HTTP_TIMEOUT_SEC", "12"))

ODIMALL_HEADERS = {
    "x-odimall-request-id": "X-OdiMall-Request-Id",
    "x-odimall-correlation-id": "X-OdiMall-Correlation-Id",
    "x-odimall-timestamp": "X-OdiMall-Timestamp",
}


def _event_headers(event: dict[str, Any]) -> dict[str, str]:
    incoming = event.get("headers") or {}
    return {str(k).lower(): str(v) for k, v in incoming.items() if v is not None}


def _base_headers(lower: dict[str, str]) -> dict[str, str]:
    out: dict[str, str] = {
        "Accept": "application/json",
        "User-Agent": "odimall-lambda-python/1.0",
    }
    for low, canon in ODIMALL_HEADERS.items():
        if lower.get(low):
            out[canon] = lower[low]
    return out


def _http_get_json(url: str, headers: dict[str, str]) -> dict[str, Any]:
    try:
        resp = requests.get(url, headers=headers, timeout=HTTP_TIMEOUT_SEC)
        try:
            body: Any = resp.json() if resp.content else {}
        except ValueError:
            body = {"raw": resp.text}
        return {"ok": resp.ok, "status": resp.status_code, "body": body}
    except requests.RequestException as e:
        return {"ok": False, "status": 0, "body": {"error": str(e), "type": type(e).__name__}}


def _response(status: int, payload: dict[str, Any]) -> dict[str, Any]:
    return {
        "statusCode": status,
        "headers": {"Content-Type": "application/json", "Cache-Control": "no-store"},
        "body": json.dumps(payload),
    }


def handler(event, context):
    lower = _event_headers(event if isinstance(event, dict) else {})
    headers = _base_headers(lower)
    started = time.time()

    catalog = _http_get_json(f"{ODIMALL_BASE_URL}/api/products", headers)
    product_count = 0
    if catalog.get("ok") and isinstance(catalog.get("body"), list):
        product_count = len(catalog["body"])
    elif catalog.get("ok") and isinstance(catalog.get("body"), dict):
        products = catalog["body"].get("products")
        if isinstance(products, list):
            product_count = len(products)

    if not NODE_LAMBDA_URL:
        node_hop: dict[str, Any] = {
            "ok": False,
            "status": 0,
            "body": {"error": "NODE_LAMBDA_URL not configured"},
        }
    else:
        node_hop = _http_get_json(NODE_LAMBDA_URL, headers)

    ok = bool(catalog.get("ok")) and bool(node_hop.get("ok"))
    payload = {
        "service": "odimall-lambda-python",
        "runtime": "python3.12",
        "role": "orchestrator",
        "ok": ok,
        "elapsedMs": int((time.time() - started) * 1000),
        "odimall": {
            "baseUrl": ODIMALL_BASE_URL,
            "path": "/api/products",
            "status": catalog.get("status"),
            "productCount": product_count,
        },
        "next": node_hop.get("body") if isinstance(node_hop.get("body"), dict) else node_hop,
        "aws": {
            "functionName": getattr(context, "function_name", None),
            "requestId": getattr(context, "aws_request_id", None),
            "memoryLimitMB": getattr(context, "memory_limit_in_mb", None),
        },
    }
    return _response(200 if ok else 502, payload)
