package com.odimall.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * OdiMall Lambda fulfillment hop (Java).
 *
 * No manual OTEL. Inbound Function URL context and outbound HTTP propagation
 * are left to the Odigos Lambda layer.
 */
public class Handler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final String ODIMALL_BASE_URL = stripSlash(env(
            "ODIMALL_BASE_URL",
            "http://k8s-odimall-odimallf-ef066ebb60-26217367.us-east-2.elb.amazonaws.com"));
    private static final Duration TIMEOUT = Duration.ofSeconds(
            Long.parseLong(env("HTTP_TIMEOUT_SEC", "12")));

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        long started = System.currentTimeMillis();
        Map<String, String> fwd = odimallHeaders(eventHeaderMap(event));

        HttpResult catalog = httpGetJson(ODIMALL_BASE_URL + "/api/products", fwd);
        int productCount = 0;
        String sampleName = null;
        if (catalog.ok && catalog.body != null) {
            if (catalog.body.isArray()) {
                productCount = catalog.body.size();
                if (productCount > 0) {
                    JsonNode first = catalog.body.get(0);
                    if (first != null && first.has("name")) {
                        sampleName = first.get("name").asText();
                    }
                }
            } else if (catalog.body.has("products") && catalog.body.get("products").isArray()) {
                productCount = catalog.body.get("products").size();
            }
        }

        boolean ok = catalog.ok;

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("service", "odimall-lambda-java");
        payload.put("runtime", "java21");
        payload.put("role", "fulfillment");
        payload.put("ok", ok);
        payload.put("elapsedMs", System.currentTimeMillis() - started);

        ObjectNode odimall = payload.putObject("odimall");
        odimall.put("baseUrl", ODIMALL_BASE_URL);
        odimall.put("path", "/api/products");
        odimall.put("status", catalog.status);
        odimall.put("productCount", productCount);
        if (sampleName != null) {
            odimall.put("sampleProduct", sampleName);
        }

        ObjectNode aws = payload.putObject("aws");
        if (context != null) {
            aws.put("functionName", context.getFunctionName());
            aws.put("requestId", context.getAwsRequestId());
            aws.put("memoryLimitMB", context.getMemoryLimitInMB());
        }

        payload.put("message", ok
                ? "Java fulfillment hop complete — catalog reachable from Lambda"
                : "Java fulfillment hop failed to reach OdiMall");

        Map<String, Object> response = new HashMap<>();
        response.put("statusCode", ok ? 200 : 502);
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Cache-Control", "no-store");
        response.put("headers", headers);
        try {
            response.put("body", MAPPER.writeValueAsString(payload));
        } catch (Exception e) {
            response.put("statusCode", 500);
            response.put("body", "{\"error\":\"" + e.getClass().getSimpleName() + "\"}");
        }
        return response;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> eventHeaderMap(Map<String, Object> event) {
        Map<String, String> lower = new HashMap<>();
        if (event == null) {
            return lower;
        }
        Object headersObj = event.get("headers");
        if (!(headersObj instanceof Map)) {
            return lower;
        }
        Map<String, Object> incoming = (Map<String, Object>) headersObj;
        for (Map.Entry<String, Object> e : incoming.entrySet()) {
            if (e.getValue() != null) {
                lower.put(e.getKey().toLowerCase(Locale.ROOT), String.valueOf(e.getValue()));
            }
        }
        return lower;
    }

    private static Map<String, String> odimallHeaders(Map<String, String> lower) {
        Map<String, String> out = new HashMap<>();
        out.put("Accept", "application/json");
        out.put("User-Agent", "odimall-lambda-java/1.0");
        putIfPresent(lower, out, "x-odimall-request-id", "X-OdiMall-Request-Id");
        putIfPresent(lower, out, "x-odimall-correlation-id", "X-OdiMall-Correlation-Id");
        putIfPresent(lower, out, "x-odimall-timestamp", "X-OdiMall-Timestamp");
        return out;
    }

    private static void putIfPresent(Map<String, String> lower, Map<String, String> out,
                                     String low, String canon) {
        String v = lower.get(low);
        if (v != null && !v.isBlank()) {
            out.put(canon, v);
        }
    }

    private static HttpResult httpGetJson(String url, Map<String, String> headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .GET();
            for (Map.Entry<String, String> h : headers.entrySet()) {
                b.header(h.getKey(), h.getValue());
            }
            HttpResponse<InputStream> res = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            String raw = new String(res.body().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode body;
            try {
                body = raw.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(raw);
            } catch (Exception parseErr) {
                body = MAPPER.createObjectNode().put("raw", raw);
            }
            boolean ok = res.statusCode() >= 200 && res.statusCode() < 300;
            return new HttpResult(ok, res.statusCode(), body);
        } catch (Exception e) {
            ObjectNode err = MAPPER.createObjectNode();
            err.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            err.put("type", e.getClass().getSimpleName());
            return new HttpResult(false, 0, err);
        }
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static String stripSlash(String s) {
        if (s.endsWith("/")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }

    private record HttpResult(boolean ok, int status, JsonNode body) {}
}
