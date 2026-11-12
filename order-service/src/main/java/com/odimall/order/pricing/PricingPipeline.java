package com.odimall.order.pricing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/** Prices validated order lines: subtotal, then promotions, then an invariant check. */
@Component
public class PricingPipeline {

    private static final Logger logger = LoggerFactory.getLogger(PricingPipeline.class);

    private final PromotionRuleCatalog catalog;
    private final DiscountStackResolver resolver;
    private final OrderTotalGuard guard;

    public PricingPipeline(PromotionRuleCatalog catalog, DiscountStackResolver resolver, OrderTotalGuard guard) {
        this.catalog = catalog;
        this.resolver = resolver;
        this.guard = guard;
    }

    public PricedOrder price(String orderId, List<Map<String, Object>> validatedItems) {
        BigDecimal subtotal = validatedItems.stream()
                .map(item -> (BigDecimal) item.get("lineTotal"))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        List<Long> productIds = validatedItems.stream()
                .map(item -> ((Number) item.get("productId")).longValue())
                .toList();

        List<PromotionRule> rules = catalog.activeRulesFor(productIds);
        DiscountResolution resolution = resolver.resolve(orderId, validatedItems, rules);

        BigDecimal discount = resolution.getDiscountAmount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal discountedSubtotal = subtotal.subtract(discount);

        guard.verify(orderId, subtotal, discountedSubtotal);

        logger.info("Order priced: id={} subtotal={} discount={} promos={}", orderId, subtotal, discount, rules.size());
        return new PricedOrder(subtotal, discount, discountedSubtotal, rules.toString());
    }
}
