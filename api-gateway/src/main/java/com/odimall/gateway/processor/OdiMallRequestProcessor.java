package com.odimall.gateway.processor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class OdiMallRequestProcessor {

    private static final Logger logger = LoggerFactory.getLogger(OdiMallRequestProcessor.class);

    private final AtomicLong totalRequestsProcessed = new AtomicLong(0);
    private final AtomicLong totalProcessingTimeNanos = new AtomicLong(0);

    /**
     * Processes an incoming gateway request: logs it, tracks metrics, and returns
     * a summary string with timing and throughput data.
     */
    public String processRequest(String requestId, String endpoint, String method) {
        long startNanos = System.nanoTime();
        Instant receivedAt = Instant.now();

        logger.info("Processing request id={} method={} endpoint={}", requestId, method, endpoint);

        long requestNumber = totalRequestsProcessed.incrementAndGet();

        String normalizedEndpoint = normalizeEndpoint(endpoint);
        String routeCategory = categorizeRoute(normalizedEndpoint);

        long elapsedNanos = System.nanoTime() - startNanos;
        long cumulativeNanos = totalProcessingTimeNanos.addAndGet(elapsedNanos);
        double avgProcessingMicros = (cumulativeNanos / 1000.0) / requestNumber;

        String summary = String.format(
                "request=%s | method=%s | endpoint=%s | route=%s | received=%s | processing_us=%.1f | total_requests=%d | avg_processing_us=%.1f",
                requestId, method, normalizedEndpoint, routeCategory,
                receivedAt.toString(), elapsedNanos / 1000.0,
                requestNumber, avgProcessingMicros
        );

        logger.info("Request processed: {}", summary);
        return summary;
    }

    /**
     * Builds custom OdiMall headers to propagate through the service mesh.
     */
    public Map<String, String> buildCustomHeaders(String requestId, String correlationId) {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-OdiMall-Request-Id", requestId);
        headers.put("X-OdiMall-Correlation-Id", correlationId);
        headers.put("X-OdiMall-Timestamp", Instant.now().toString());
        headers.put("X-OdiMall-Gateway-Version", "1.0.0");
        return headers;
    }

    private String normalizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isEmpty()) {
            return "/";
        }
        return endpoint.replaceAll("/+", "/").replaceAll("/$", "");
    }

    private String categorizeRoute(String endpoint) {
        if (endpoint.startsWith("/products")) return "product-service";
        if (endpoint.startsWith("/cart")) return "cart-service";
        if (endpoint.startsWith("/users")) return "user-service";
        if (endpoint.startsWith("/orders")) return "order-service";
        return "unknown";
    }
}
