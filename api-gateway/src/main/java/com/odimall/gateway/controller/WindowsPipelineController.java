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
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@RestController
public class WindowsPipelineController {

    private final RestTemplate restTemplate;

    @Value("${windows-pipeline.base-url:}")
    private String windowsBaseUrl;

    public WindowsPipelineController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @GetMapping("/windows-pipeline/run")
    public ResponseEntity<?> run(HttpServletRequest request) {
        if (windowsBaseUrl == null || windowsBaseUrl.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "error", "windows_pipeline_not_configured",
                            "message", "Set WINDOWS_PIPELINE_BASE_URL to the Windows .NET base (e.g. http://203.0.113.20:9201)."
                    ));
        }

        String base = windowsBaseUrl.endsWith("/")
                ? windowsBaseUrl.substring(0, windowsBaseUrl.length() - 1)
                : windowsBaseUrl;
        String url = base + "/run";

        HttpHeaders headers = new HttpHeaders();
        for (String name : List.of("traceparent", "tracestate", "baggage")) {
            String v = request.getHeader(name);
            if (v != null && !v.isBlank()) {
                headers.set(name, v);
            }
        }
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
        } catch (RestClientException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "error", "windows_pipeline_upstream_failed",
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
