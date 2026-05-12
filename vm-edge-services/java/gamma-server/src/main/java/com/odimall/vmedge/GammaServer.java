package com.odimall.vmedge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Terminal hop: reads {@code JDBC_URL} (Postgres) and returns JSON including a DB row.
 */
public final class GammaServer {

    private GammaServer() {}

    public static void main(String[] args) throws Exception {
        String host = env("GAMMA_BIND_HOST", "127.0.0.1");
        int port = Integer.parseInt(env("GAMMA_PORT", "9103"));
        String jdbcUrl = env("JDBC_URL", "jdbc:postgresql://127.0.0.1:5432/odimall_vm?user=odimall_vm&password=odimall_vm_demo");

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/chain", new ChainHandler(jdbcUrl));
        server.setExecutor(null);
        server.start();
        System.err.println("vm_gamma (Java) listening on http://" + host + ":" + port + "/chain");
    }

    private static String env(String k, String d) {
        String v = System.getenv(k);
        return (v == null || v.isBlank()) ? d : v;
    }

    static final class ChainHandler implements HttpHandler {
        private final String jdbcUrl;

        ChainHandler(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
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

            List<String> traceKeys = new ArrayList<>();
            for (String n : List.of("traceparent", "tracestate", "baggage")) {
                if (header(ex, n) != null) {
                    traceKeys.add(n);
                }
            }

            boolean dbOk = false;
            String catalog = null;
            String err = null;
            List<Map<String, Object>> rows = new ArrayList<>();

            try (Connection c = DriverManager.getConnection(jdbcUrl)) {
                catalog = c.getCatalog();
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT id, note FROM demo_ping ORDER BY id ASC LIMIT 5")) {
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", rs.getLong("id"));
                        row.put("note", rs.getString("note"));
                        rows.add(row);
                    }
                }
                dbOk = true;
            } catch (Exception e) {
                err = e.getClass().getSimpleName() + ": " + e.getMessage();
            }

            String body = buildJson(traceKeys, dbOk, catalog, err, rows);
            byte[] raw = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(200, raw.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(raw);
            }
        }

        private static String header(HttpExchange ex, String name) {
            List<String> v = ex.getRequestHeaders().get(name);
            if (v != null && !v.isEmpty()) {
                return v.get(0);
            }
            String cap = name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
            v = ex.getRequestHeaders().get(cap);
            return (v != null && !v.isEmpty()) ? v.get(0) : null;
        }

        private static String buildJson(
                List<String> traceKeys,
                boolean dbOk,
                String catalog,
                String err,
                List<Map<String, Object>> rows) {
            StringBuilder sb = new StringBuilder(512);
            sb.append("{\"service\":\"gamma\",\"language\":\"java\",\"port\":9103,");
            sb.append("\"message\":\"Terminal hop; queried PostgreSQL.\",");
            sb.append("\"incoming_trace_headers\":[");
            for (int i = 0; i < traceKeys.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('"').append(escape(traceKeys.get(i))).append('"');
            }
            sb.append("],\"database\":{");
            sb.append("\"ok\":").append(dbOk);
            if (catalog != null) {
                sb.append(",\"catalog\":\"").append(escape(catalog)).append('"');
            }
            if (err != null) {
                sb.append(",\"error\":\"").append(escape(err)).append('"');
            }
            sb.append(",\"rows\":[");
            for (int i = 0; i < rows.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                Map<String, Object> row = rows.get(i);
                sb.append("{\"id\":").append(row.get("id"));
                sb.append(",\"note\":\"").append(escape(String.valueOf(row.get("note")))).append("\"}");
            }
            sb.append("]}}");
            return sb.toString();
        }

        private static String escape(String s) {
            return s.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r");
        }
    }
}
