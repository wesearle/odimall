package com.odimall.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Set;

@RestController
public class GatewayController {

    private static final Logger logger = LoggerFactory.getLogger(GatewayController.class);

    private final RestTemplate restTemplate;

    @Value("${services.product-url}")
    private String productServiceUrl;

    @Value("${services.cart-url}")
    private String cartServiceUrl;

    @Value("${services.user-url}")
    private String userServiceUrl;

    @Value("${services.order-url}")
    private String orderServiceUrl;

    public GatewayController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // --- Product routes ---

    @RequestMapping(value = "/products/**", method = {RequestMethod.GET})
    public ResponseEntity<String> proxyProducts(HttpServletRequest request) {
        return proxy(request, productServiceUrl);
    }

    // --- Cart routes ---

    @RequestMapping(value = "/cart/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<String> proxyCart(HttpServletRequest request, @RequestBody(required = false) String body) {
        return proxy(request, cartServiceUrl, body);
    }

    // --- User routes ---

    @RequestMapping(value = "/users/**", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<String> proxyUsers(HttpServletRequest request, @RequestBody(required = false) String body) {
        return proxy(request, userServiceUrl, body);
    }

    // --- Order routes ---

    @RequestMapping(value = "/orders/**", method = {RequestMethod.POST})
    public ResponseEntity<String> proxyOrders(HttpServletRequest request, @RequestBody(required = false) String body) {
        return proxy(request, orderServiceUrl, body);
    }

    private ResponseEntity<String> proxy(HttpServletRequest request, String serviceBaseUrl) {
        return proxy(request, serviceBaseUrl, null);
    }

    private ResponseEntity<String> proxy(HttpServletRequest request, String serviceBaseUrl, String body) {
        String path = request.getRequestURI();
        String queryString = request.getQueryString();
        String targetUrl = serviceBaseUrl + path;
        if (queryString != null) {
            targetUrl += "?" + queryString;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String requestId = (String) request.getAttribute("X-OdiMall-Request-Id");
        String correlationId = (String) request.getAttribute("X-OdiMall-Correlation-Id");
        String timestamp = (String) request.getAttribute("X-OdiMall-Timestamp");

        if (requestId != null) headers.set("X-OdiMall-Request-Id", requestId);
        if (correlationId != null) headers.set("X-OdiMall-Correlation-Id", correlationId);
        if (timestamp != null) headers.set("X-OdiMall-Timestamp", timestamp);

        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        logger.info("Proxying {} {} -> {}", method, path, targetUrl);

        try {
            ResponseEntity<String> response = restTemplate.exchange(targetUrl, method, entity, String.class);
            HttpHeaders responseHeaders = new HttpHeaders();
            Set<String> hopByHop = Set.of("transfer-encoding", "connection", "keep-alive",
                    "proxy-authenticate", "proxy-authorization", "te", "trailer", "upgrade");
            response.getHeaders().forEach((name, values) -> {
                if (!hopByHop.contains(name.toLowerCase())) {
                    responseHeaders.put(name, values);
                }
            });
            return ResponseEntity.status(response.getStatusCode())
                    .headers(responseHeaders)
                    .body(response.getBody());
        } catch (HttpClientErrorException e) {
            logger.warn("Upstream returned {}: {}", e.getStatusCode(), e.getMessage());
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        } catch (Exception e) {
            logger.error("Error proxying request to {}: {}", targetUrl, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Service unavailable", "service", targetUrl).toString());
        }
    }
}
