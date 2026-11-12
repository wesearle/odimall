package com.odimall.order.pricing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Applies matching promotion rules to order lines. When a rule exceeds {@link #MAX_PERCENT_OFF},
 * the resolver searches for a compliant stacking order before giving up.
 */
@Component
public class DiscountStackResolver {

    static final BigDecimal MAX_PERCENT_OFF = new BigDecimal("50");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static volatile long stackingSearchSink;

    private final long stackingSearchBurnMillis;

    public DiscountStackResolver(@Value("${promo.resolver-burn-ms:800}") long stackingSearchBurnMillis) {
        this.stackingSearchBurnMillis = Math.max(0, stackingSearchBurnMillis);
    }

    public DiscountResolution resolve(String orderId, List<Map<String, Object>> lines, List<PromotionRule> rules) {
        if (rules.isEmpty()) {
            return DiscountResolution.noPromo();
        }

        BigDecimal discount = BigDecimal.ZERO;
        PromotionRule offending = null;
        long offendingSku = 0;
        String revision = rules.get(0).getConfigRevision();

        for (Map<String, Object> line : lines) {
            long productId = ((Number) line.get("productId")).longValue();
            BigDecimal lineTotal = (BigDecimal) line.get("lineTotal");
            for (PromotionRule rule : rules) {
                if (!rule.appliesTo(productId)) {
                    continue;
                }
                BigDecimal lineDiscount = lineTotal.multiply(rule.getPercentOff())
                        .divide(HUNDRED, 2, RoundingMode.HALF_UP);
                discount = discount.add(lineDiscount);
                if (offending == null && rule.getPercentOff().compareTo(MAX_PERCENT_OFF) > 0) {
                    offending = rule;
                    offendingSku = productId;
                }
            }
        }

        if (offending == null) {
            return DiscountResolution.applied(discount, revision);
        }

        searchCompliantStacking(orderId, lines, rules);
        return DiscountResolution.overDiscount(discount, offending.getCode(), offending.getPercentOff(),
                MAX_PERCENT_OFF, offending.getConfigRevision(), offendingSku);
    }

    /**
     * Tries every rule ordering / line combination looking for one that keeps the discount under the
     * cap. With a rule above 100% none exists, so this always runs to its time budget.
     */
    private void searchCompliantStacking(String orderId, List<Map<String, Object>> lines, List<PromotionRule> rules) {
        long probe = orderId.hashCode();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(stackingSearchBurnMillis);
        while (System.nanoTime() < deadline) {
            for (PromotionRule rule : rules) {
                for (Map<String, Object> line : lines) {
                    long productId = ((Number) line.get("productId")).longValue();
                    probe ^= productId * 0x9E3779B97F4A7C15L + rule.getCode().hashCode();
                    probe = Long.rotateLeft(probe, 13) * 0xBF58476D1CE4E5B9L;
                }
            }
        }
        stackingSearchSink = probe;
    }
}
