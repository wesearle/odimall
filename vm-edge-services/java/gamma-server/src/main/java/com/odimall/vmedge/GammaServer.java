package com.odimall.vmedge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Terminal hop: Spring Boot server that queries PostgreSQL via JDBC and returns JSON including DB rows.
 */
@SpringBootApplication
public class GammaServer {

    public static void main(String[] args) {
        SpringApplication.run(GammaServer.class, args);
    }

    /**
     * Keep compatibility with {@code JDBC_URL} that embeds user/password query params
     * (same format as the previous non-Spring gamma service / {@code gamma.env}).
     */
    @Bean
    DataSource dataSource(@Value("${spring.datasource.url}") String jdbcUrl) {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(org.postgresql.Driver.class);
        ds.setUrl(jdbcUrl);
        return ds;
    }

    @RestController
    static class ChainController {
        private static final List<String> TRACE_HEADERS = List.of("traceparent", "tracestate", "baggage");

        private final JdbcTemplate jdbc;

        ChainController(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @GetMapping(value = "/chain", produces = MediaType.APPLICATION_JSON_VALUE)
        ResponseEntity<String> chain(HttpServletRequest request) {
            List<String> traceKeys = new ArrayList<>();
            for (String name : TRACE_HEADERS) {
                if (header(request, name) != null) {
                    traceKeys.add(name);
                }
            }

            boolean dbOk = false;
            String catalog = null;
            String err = null;
            List<Map<String, Object>> rows = new ArrayList<>();

            try {
                catalog = jdbc.queryForObject("SELECT current_database()", String.class);
                rows = jdbc.query(
                        "SELECT id, note FROM demo_ping ORDER BY id ASC LIMIT 5",
                        (rs, rowNum) -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("id", rs.getLong("id"));
                            row.put("note", rs.getString("note"));
                            return row;
                        });
                dbOk = true;
            } catch (Exception e) {
                err = e.getClass().getSimpleName() + ": " + e.getMessage();
            }

            return ResponseEntity.ok(buildJson(traceKeys, dbOk, catalog, err, rows));
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
            if (s == null) {
                return "";
            }
            return s.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r");
        }
    }
}
