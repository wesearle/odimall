package com.odimall.order.pricing;

import java.math.BigDecimal;
import java.util.Set;

/** A single promotion rule as loaded from the promo-rules config bundle. */
public final class PromotionRule {

    private final String code;
    private final BigDecimal percentOff;
    private final Set<Long> eligibleProductIds;
    private final String configRevision;

    public PromotionRule(String code, BigDecimal percentOff, Set<Long> eligibleProductIds, String configRevision) {
        this.code = code;
        this.percentOff = percentOff;
        this.eligibleProductIds = Set.copyOf(eligibleProductIds);
        this.configRevision = configRevision;
    }

    public String getCode() {
        return code;
    }

    public BigDecimal getPercentOff() {
        return percentOff;
    }

    public Set<Long> getEligibleProductIds() {
        return eligibleProductIds;
    }

    public String getConfigRevision() {
        return configRevision;
    }

    public boolean appliesTo(long productId) {
        return eligibleProductIds.contains(productId);
    }

    @Override
    public String toString() {
        return code + "@" + configRevision;
    }
}
