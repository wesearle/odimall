package com.odimall.order.pricing;

import java.math.BigDecimal;

/**
 * Result of {@link DiscountStackResolver#resolve}. {@link #toString()} stays short because
 * captured return values are often truncated around 128 characters.
 */
public final class DiscountResolution {

    public enum Status { APPLIED, NO_PROMO, OVER_DISCOUNT }

    private final Status status;
    private final BigDecimal discountAmount;
    private final String offendingRule;
    private final BigDecimal offendingPercent;
    private final BigDecimal percentCap;
    private final String configRevision;
    private final long offendingSku;

    private DiscountResolution(Status status, BigDecimal discountAmount, String offendingRule,
                               BigDecimal offendingPercent, BigDecimal percentCap,
                               String configRevision, long offendingSku) {
        this.status = status;
        this.discountAmount = discountAmount;
        this.offendingRule = offendingRule;
        this.offendingPercent = offendingPercent;
        this.percentCap = percentCap;
        this.configRevision = configRevision;
        this.offendingSku = offendingSku;
    }

    static DiscountResolution noPromo() {
        return new DiscountResolution(Status.NO_PROMO, BigDecimal.ZERO, null, null, null, null, 0);
    }

    static DiscountResolution applied(BigDecimal discountAmount, String configRevision) {
        return new DiscountResolution(Status.APPLIED, discountAmount, null, null, null, configRevision, 0);
    }

    static DiscountResolution overDiscount(BigDecimal discountAmount, String rule, BigDecimal percent,
                                           BigDecimal cap, String configRevision, long sku) {
        return new DiscountResolution(Status.OVER_DISCOUNT, discountAmount, rule, percent, cap, configRevision, sku);
    }

    public Status getStatus() {
        return status;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    @Override
    public String toString() {
        return switch (status) {
            case OVER_DISCOUNT -> "OVER_DISCOUNT|rule=" + offendingRule
                    + "|pct=" + offendingPercent.stripTrailingZeros().toPlainString()
                    + "|cap=" + percentCap.stripTrailingZeros().toPlainString()
                    + "|cfg=promo-rules@" + configRevision
                    + "|sku=" + offendingSku
                    + "|discount=" + discountAmount.toPlainString();
            case APPLIED -> "APPLIED|discount=" + discountAmount.toPlainString() + "|cfg=promo-rules@" + configRevision;
            case NO_PROMO -> "NO_PROMO|discount=0";
        };
    }
}
