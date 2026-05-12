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
public class VmPipelineController {

    private final RestTemplate restTemplate;

    @Value("${vm-pipeline.alpha-url:}")
    private String alphaBaseUrl;

    public VmPipelineController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @GetMapping("/vm-pipeline/run")
    public ResponseEntity<?> run(HttpServletRequest request) {
        if (alphaBaseUrl == null || alphaBaseUrl.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "error", "vm_pipeline_not_configured",
                            "message", "Set VM_PIPELINE_ALPHA_URL to the VM entry base (e.g. http://203.0.113.10:9101)."
                    ));
        }

        String base = alphaBaseUrl.endsWith("/")
                ? alphaBaseUrl.substring(0, alphaBaseUrl.length() - 1)
                : alphaBaseUrl;
        String url = base + "/chain";

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
                            "error", "vm_pipeline_upstream_failed",
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
