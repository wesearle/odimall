package com.odimall.order.policy;

/**
 * Outcome of {@link RetailFulfillmentGate#assessPipelineCoherence(String, java.util.List)}.
 * <p>When {@link #retailContinuationGranted} is {@code false}, operators use Odigos custom
 * instrumentation on that method. The eBPF agent usually records {@code return.value} from
 * {@link #toString()} (not field reflection), so {@link #toString()} embeds the ledger attestation
 * for a readable trace. The attestation is still intentionally not written to application logs.
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
     * Surfaces policy outcome as one line so tools that stringify the return value (e.g. Odigos
     * custom instrumentation {@code return.value}) show the ledger instead of {@code Class@hash}.
     */
    @Override
    public String toString() {
        return "RetailPipelineAssessment{retailContinuationGranted=" + retailContinuationGranted
                + ", fulfillmentLedgerAttestation=" + fulfillmentLedgerAttestation + '}';
    }
}
