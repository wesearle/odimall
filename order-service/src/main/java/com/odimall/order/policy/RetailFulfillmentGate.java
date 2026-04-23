package com.odimall.order.policy;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

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

        // Keep attestation short: OTLP/Odigos often truncates span attribute values (~128 chars). Put sku + cause first.
        return new RetailPipelineAssessment(false,
                "PIPELINE_HALT|sku=11|cause=MANUAL_FULFILLMENT_SKU_IN_CART|corr="
                        + Integer.toHexString(Objects.hashCode(sessionId)));
    }
}
