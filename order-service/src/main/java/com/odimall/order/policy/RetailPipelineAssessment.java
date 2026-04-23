package com.odimall.order.policy;

/**
 * Outcome of {@link RetailFulfillmentGate#assessPipelineCoherence(String, java.util.List)}.
 * <p>When {@link #retailContinuationGranted} is {@code false}, operators use Odigos custom
 * instrumentation on that method. The eBPF agent usually records {@code return.value} from
 * {@link #toString()} (not field reflection), so {@link #toString()} stays compact for Odigos
 * {@code return.value}, which is often length-capped (~128 chars). The attestation is still not logged.
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

    /**
     * Compact for Odigos {@code return.value}: long prefixes get truncated before the attestation.
     */
    @Override
    public String toString() {
        return retailContinuationGranted
                ? ("OK|" + fulfillmentLedgerAttestation)
                : ("DENY|" + fulfillmentLedgerAttestation);
    }
}
