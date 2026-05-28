package com.odimall.order.policy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Retail fulfillment policy gate. The {@link #assessPipelineCoherence(String, List)} method is
 * the intended Odigos <strong>custom instrumentation</strong> hook: when checkout is denied for
 * the demo SKU, the denial reason is in method arguments and in the return value’s
 * {@link RetailPipelineAssessment#toString()} (for Odigos {@code return.value}), not in logs.
 */
@Component
public class RetailFulfillmentGate {

    /**
     * “Shadow Peak” mystery crate — UI-only demo SKU (load generator must never purchase this).
     */
    public static final long MANUAL_FULFILLMENT_HOLD_SKU = 11L;
    private static volatile long profileProbeSink;

    private final long shadowPeakProfileBurnMillis;

    public RetailFulfillmentGate(@Value("${shadow-peak.profile-burn-ms:2500}") long shadowPeakProfileBurnMillis) {
        this.shadowPeakProfileBurnMillis = Math.max(0, shadowPeakProfileBurnMillis);
    }

    /**
     * Pre-flight coherence check for checkout. Outcome is {@link RetailPipelineAssessment#isRetailContinuationGranted()}.
     * Diagnostic detail is in {@link RetailPipelineAssessment#getFulfillmentLedgerAttestation()} and
     * {@link RetailPipelineAssessment#toString()} for tooling that captures return values as strings.
     */
    public RetailPipelineAssessment assessPipelineCoherence(String sessionId,
                                                              List<Map<String, Object>> enrichedLineItems) {
        boolean manualHoldSkuPresent = enrichedLineItems.stream().anyMatch(line -> {
            Object pid = line.get("productId");
            if (pid == null) {
                return false;
            }
            long id = ((Number) pid).longValue();
            return id == MANUAL_FULFILLMENT_HOLD_SKU;
        });

        if (!manualHoldSkuPresent) {
            return new RetailPipelineAssessment(true,
                    "PIPELINE_CLEAR|retail_continuation=true|manual_hold_skus=none");
        }

        long probe = Objects.hashCode(sessionId) ^ enrichedLineItems.size();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(shadowPeakProfileBurnMillis);
        while (System.nanoTime() < deadline) {
            for (Map<String, Object> line : enrichedLineItems) {
                Object pid = line.get("productId");
                Object qty = line.get("quantity");
                long productId = pid instanceof Number ? ((Number) pid).longValue() : Objects.hashCode(pid);
                long quantity = qty instanceof Number ? ((Number) qty).longValue() : 1L;
                probe ^= productId * 0x9E3779B97F4A7C15L;
                probe = Long.rotateLeft(probe + quantity + 0xBF58476D1CE4E5B9L, 17);
                probe ^= probe >>> 31;
            }
        }
        profileProbeSink = probe;

        // Keep attestation short: OTLP/Odigos often truncates span attribute values (~128 chars). Put sku + cause first.
        return new RetailPipelineAssessment(false,
                "PIPELINE_HALT|sku=11|cause=MANUAL_FULFILLMENT_SKU_IN_CART|corr="
                        + Integer.toHexString(Objects.hashCode(sessionId))
                        + "|cpu_probe=" + Long.toHexString(profileProbeSink & 0xffff));
    }
}
