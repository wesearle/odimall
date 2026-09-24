package com.odimall.vmedge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;

/**
 * Middle hop: Spring Boot server that calls gamma via {@link RestClient} and forwards W3C trace headers.
 */
@SpringBootApplication
@EnableConfigurationProperties(BetaServer.GammaProperties.class)
public class BetaServer {

    public static void main(String[] args) {
        SpringApplication.run(BetaServer.class, args);
    }

    @Bean
    RestClient restClient(RestClient.Builder builder) {
        return builder.build();
    }

    @ConfigurationProperties(prefix = "gamma")
    public record GammaProperties(String baseUrl) {
        public GammaProperties {
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "http://127.0.0.1:9103";
            } else if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
        }

        String chainUrl() {
            return baseUrl + "/chain";
        }
    }

    @RestController
    static class ChainController {
        private static final List<String> TRACE_HEADERS = List.of("traceparent", "tracestate", "baggage");

        private final RestClient restClient;
        private final GammaProperties gamma;

        ChainController(RestClient restClient, GammaProperties gamma) {
            this.restClient = restClient;
            this.gamma = gamma;
        }

        @GetMapping(value = "/chain", produces = MediaType.APPLICATION_JSON_VALUE)
        ResponseEntity<String> chain(HttpServletRequest request) {
            try {
                String downstream = restClient.get()
                        .uri(gamma.chainUrl())
                        .headers(headers -> {
                            for (String name : TRACE_HEADERS) {
                                String value = header(request, name);
                                if (value != null) {
                                    headers.set(name, value);
                                }
                            }
                        })
                        .retrieve()
                        .body(String.class);
                if (downstream == null || downstream.isBlank()) {
                    downstream = "{}";
                }
                String body = "{\"service\":\"beta\",\"language\":\"java\",\"port\":9102,"
                        + "\"message\":\"Middle hop; forwarded to Java gamma.\",\"downstream\":"
                        + downstream
                        + "}";
                return ResponseEntity.ok(body);
            } catch (RestClientResponseException e) {
                String downstream = e.getResponseBodyAsString();
                if (downstream == null || downstream.isBlank()) {
                    downstream = "{}";
                }
                return ResponseEntity.status(e.getStatusCode()).body(downstream);
            } catch (Exception e) {
                String detail = e.getMessage() == null ? "" : e.getMessage()
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"");
                return ResponseEntity.status(502)
                        .body("{\"error\":\"gamma_unreachable\",\"detail\":\"" + detail + "\"}");
            }
        }

        private static String header(HttpServletRequest request, String name) {
            String value = request.getHeader(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
            String cap = name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
            value = request.getHeader(cap);
            return (value != null && !value.isBlank()) ? value : null;
        }
    }
}
