package com.odimall.order.trace;

/**
 * Stores the incoming W3C traceparent header on a per-request ThreadLocal so that
 * SQL commenter can propagate it into MySQL queries. This is NOT OpenTelemetry
 * instrumentation — it simply reads an HTTP header and makes it available for
 * appending as a SQL comment so the MySQL eBPF agent can stitch traces together.
 */
public final class TraceContextHolder {

    private static final ThreadLocal<String> TRACEPARENT = new ThreadLocal<>();

    private TraceContextHolder() {}

    public static void set(String traceparent) {
        TRACEPARENT.set(traceparent);
    }

    public static String get() {
        return TRACEPARENT.get();
    }

    public static void clear() {
        TRACEPARENT.remove();
    }

    public static String appendComment(String sql) {
        String tp = TRACEPARENT.get();
        if (tp != null && !tp.isEmpty()) {
            return sql + " /*traceparent='" + tp + "'*/";
        }
        return sql;
    }
}
