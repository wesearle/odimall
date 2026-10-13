package com.odimall.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
public class LambdaPipelineController {

    private final RestTemplate restTemplate;

    @Value("${lambda-pipeline.url:}")
    private String lambdaPipelineUrl;

    public LambdaPipelineController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @GetMapping("/lambda-pipeline/run")
    public ResponseEntity<?> run(HttpServletRequest request) {
        if (lambdaPipelineUrl == null || lambdaPipelineUrl.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "error", "lambda_pipeline_not_configured",
                            "message", "Set LAMBDA_PIPELINE_URL to the Python Lambda Function URL (entry hop)."
                    ));
        }

        String url = lambdaPipelineUrl.endsWith("/")
                ? lambdaPipelineUrl.substring(0, lambdaPipelineUrl.length() - 1)
                : lambdaPipelineUrl;

        // W3C context is injected by Odigos on RestTemplate — do not re-forward
        // inbound traceparent (that would parent Lambda to the upstream span).
        HttpHeaders headers = new HttpHeaders();
        addRequestAttributes(request, headers);

        try {
            ResponseEntity<String> r = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class);
            String body = r.getBody();
            if (body == null) {
                body = "{}";
            }
            return ResponseEntity.status(r.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body);
        } catch (HttpStatusCodeException e) {
            // Lambda Function URLs often return 502 with a useful JSON body when a hop fails.
            // Pass that through so the UI can show the chain result instead of a generic error.
            String body = e.getResponseBodyAsString(StandardCharsets.UTF_8);
            if (body == null || body.isBlank()) {
                body = "{\"error\":\"lambda_pipeline_upstream_failed\",\"message\":\""
                        + (e.getMessage() != null ? e.getMessage().replace("\"", "'") : e.getClass().getSimpleName())
                        + "\"}";
            }
            return ResponseEntity.status(e.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body);
        } catch (RestClientException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "error", "lambda_pipeline_upstream_failed",
                            "message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()
                    ));
        }
    }

    private static void addRequestAttributes(HttpServletRequest request, HttpHeaders headers) {
        Object rid = request.getAttribute("X-OdiMall-Request-Id");
        if (rid != null) {
            headers.set("X-OdiMall-Request-Id", rid.toString());
        }
        Object cid = request.getAttribute("X-OdiMall-Correlation-Id");
        if (cid != null) {
            headers.set("X-OdiMall-Correlation-Id", cid.toString());
        }
        Object ts = request.getAttribute("X-OdiMall-Timestamp");
        if (ts != null) {
            headers.set("X-OdiMall-Timestamp", ts.toString());
        }
    }
}
