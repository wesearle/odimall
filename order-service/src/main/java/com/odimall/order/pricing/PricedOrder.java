package com.odimall.order.pricing;

import java.math.BigDecimal;

/** Output of {@link PricingPipeline#price}. */
public record PricedOrder(BigDecimal subtotal, BigDecimal discount, BigDecimal discountedSubtotal, String promotions) {

    @Override
    public String toString() {
        return "PRICED|subtotal=" + subtotal.toPlainString()
                + "|discount=" + discount.toPlainString()
                + "|net=" + discountedSubtotal.toPlainString()
                + "|promos=" + promotions;
    }
}
