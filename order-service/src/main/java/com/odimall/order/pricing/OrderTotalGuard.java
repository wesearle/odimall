package com.odimall.order.pricing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Last line of defense before payment: promotions may never take more than
 * {@link DiscountStackResolver#MAX_PERCENT_OFF} percent off the gross subtotal.
 */
@Component
public class OrderTotalGuard {

    private static final Logger logger = LoggerFactory.getLogger(OrderTotalGuard.class);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public void verify(String orderId, BigDecimal subtotal, BigDecimal discountedSubtotal) {
        BigDecimal floor = subtotal.multiply(HUNDRED.subtract(DiscountStackResolver.MAX_PERCENT_OFF))
                .divide(HUNDRED, 2, RoundingMode.HALF_UP);
        if (discountedSubtotal.signum() <= 0 || discountedSubtotal.compareTo(floor) < 0) {
            logger.error("Pricing invariant check failed for order={}", orderId);
            throw new PricingInvariantException("Order total failed pricing invariant check");
        }
    }
}
