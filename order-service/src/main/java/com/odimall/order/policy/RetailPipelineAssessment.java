package com.odimall.order.policy;

/**
 * Outcome of {@link RetailFulfillmentGate#assessPipelineCoherence(String, java.util.List)}.
 * <p>When {@link #retailContinuationGranted} is {@code false}, operators are expected to use
 * custom instrumentation (e.g. Odigos) on that method to inspect the return value and arguments—
 * the attestation string is intentionally not written to application logs.
 */
public final class RetailPipelineAssessment {

    private final boolean retailContinuationGranted;
    private final String fulfillmentLedgerAttestation;

    public RetailPipelineAssessment(boolean retailContinuationGranted, String fulfillmentLedgerAttestation) {
        this.retailContinuationGranted = retailContinuationGranted;
        this.fulfillmentLedgerAttestation = fulfillmentLedgerAttestation;
    }

    public boolean isRetailContinuationGranted() {
        return retailContinuationGranted;
    }

    /**
     * Internal settlement attestation (for capture via custom instrumentation, not stdout logs).
     */
    public String getFulfillmentLedgerAttestation() {
        return fulfillmentLedgerAttestation;
    }
}
