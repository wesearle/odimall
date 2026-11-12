package com.odimall.order.pricing;

/** Thrown when a priced order fails a sanity invariant. */
public class PricingInvariantException extends RuntimeException {
    public PricingInvariantException(String message) {
        super(message);
    }
}
