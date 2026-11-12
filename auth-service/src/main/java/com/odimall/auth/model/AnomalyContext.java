package com.odimall.auth.model;

/**
 * Per-request origin context derived from headers the gateway forwards. A
 * "known good" login arrives without these markers; the attack sets an unfamiliar
 * X-Forwarded-For / User-Agent and X-OdiMall-Origin: anomalous.
 *
 * Instrument methods that take this so the anomalous origin shows up in the span.
 */
public class AnomalyContext {
    private final String clientIp;
    private final String userAgent;
    private final boolean anomalous;

    public AnomalyContext(String clientIp, String userAgent, boolean anomalous) {
        this.clientIp = clientIp;
        this.userAgent = userAgent;
        this.anomalous = anomalous;
    }

    public String getClientIp() { return clientIp; }
    public String getUserAgent() { return userAgent; }
    public boolean isAnomalous() { return anomalous; }

    @Override
    public String toString() {
        return "AnomalyContext{clientIp='" + clientIp + "', userAgent='" + userAgent
                + "', anomalous=" + anomalous + "}";
    }
}
