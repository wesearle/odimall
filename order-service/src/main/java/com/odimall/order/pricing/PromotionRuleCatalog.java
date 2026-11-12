package com.odimall.order.pricing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * In-memory view of the promo-rules config bundle (normally synced from a pricing config repo).
 * {@code promo.fault-enabled} ({@code PROMO_FAULT_ENABLED}) loads the newer bundle revision —
 * see the README "Promo pricing fault" section.
 */
@Component
public class PromotionRuleCatalog {

    private static final Logger logger = LoggerFactory.getLogger(PromotionRuleCatalog.class);

    /** Trail Blazer Hiking Boots (#1) and Peak Performance Jacket (#7). */
    static final Set<Long> OUTERWEAR_SKUS = Set.of(1L, 7L);
    /** Alpine Trekking Poles (#4), First Aid Kit (#8), Headlamp (#9), Water Filter (#10). */
    static final Set<Long> ACCESSORY_SKUS = Set.of(4L, 8L, 9L, 10L);

    private final List<PromotionRule> rules;

    public PromotionRuleCatalog(@Value("${promo.fault-enabled:false}") boolean faultEnabled) {
        String revision = faultEnabled ? "v42" : "v41";
        BigDecimal outerwearPct = faultEnabled ? new BigDecimal("150") : new BigDecimal("15.0");
        this.rules = List.of(
                new PromotionRule("FALL26-OUTERWEAR", outerwearPct, OUTERWEAR_SKUS, revision),
                new PromotionRule("TRAIL-ESSENTIALS", new BigDecimal("10.0"), ACCESSORY_SKUS, revision)
        );
        logger.info("Promotion catalog loaded: revision={} rules={}", revision, rules.size());
    }

    /**
     * Returns the promotion rules that match at least one product in the order.
     */
    public List<PromotionRule> activeRulesFor(List<Long> productIds) {
        return rules.stream()
                .filter(rule -> productIds.stream().anyMatch(rule::appliesTo))
                .toList();
    }
}
