package com.odimall.vmedge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Middle hop: calls gamma over HTTP and forwards W3C trace headers.
 */
public final class BetaServer {

    private BetaServer() {}

    public static void main(String[] args) throws Exception {
        String host = env("BETA_BIND_HOST", "127.0.0.1");
        int port = Integer.parseInt(env("BETA_PORT", "9102"));
        String gammaBase = trimSlash(env("GAMMA_BASE_URL", "http://127.0.0.1:9103"));

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/chain", new ChainHandler(http, gammaBase + "/chain"));
        server.setExecutor(null);
        server.start();
        System.err.println("vm_beta (Java) listening on http://" + host + ":" + port + "/chain");
    }

    private static String env(String k, String d) {
        String v = System.getenv(k);
        return (v == null || v.isBlank()) ? d : v;
    }

    private static String trimSlash(String u) {
        if (u.endsWith("/")) {
            return u.substring(0, u.length() - 1);
        }
        return u;
    }

    static final class ChainHandler implements HttpHandler {
        private final HttpClient http;
        private final String gammaChainUrl;

        ChainHandler(HttpClient http, String gammaChainUrl) {
            this.http = http;
            this.gammaChainUrl = gammaChainUrl;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            if (!"/chain".equals(ex.getRequestURI().getPath())) {
                ex.sendResponseHeaders(404, -1);
                return;
            }

            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(gammaChainUrl))
                    .GET()
                    .timeout(Duration.ofSeconds(30));

            forwardTraceHeaders(ex, rb);

            try {
                HttpResponse<String> r = http.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                String downstream = r.body() == null ? "{}" : r.body();
                if (r.statusCode() != 200) {
                    errorJson(ex, r.statusCode(), downstream);
                    return;
                }
                String body = "{\"service\":\"beta\",\"language\":\"java\",\"port\":9102,\"message\":\"Middle hop; forwarded to Java gamma.\",\"downstream\":"
                        + downstream
                        + "}";
                byte[] raw = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                ex.sendResponseHeaders(200, raw.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(raw);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                errorJson(ex, 502, "{\"error\":\"interrupted\"}");
            } catch (Exception e) {
                errorJson(ex, 502, "{\"error\":\"gamma_unreachable\",\"detail\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        }

        private static void forwardTraceHeaders(HttpExchange ex, HttpRequest.Builder rb) {
            for (String name : List.of("traceparent", "tracestate", "baggage")) {
                List<String> vals = ex.getRequestHeaders().get(name);
                if (vals != null && !vals.isEmpty()) {
                    rb.header(name, vals.get(0));
                    continue;
                }
                String cap = name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
                vals = ex.getRequestHeaders().get(cap);
                if (vals != null && !vals.isEmpty()) {
                    rb.header(name, vals.get(0));
                }
            }
        }

        private static void errorJson(HttpExchange ex, int code, String body) throws IOException {
            byte[] raw = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(code, raw.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(raw);
            }
        }

        private static String escapeJson(String s) {
            if (s == null) {
                return "";
            }
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
