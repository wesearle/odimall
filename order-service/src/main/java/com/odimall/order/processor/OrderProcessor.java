package com.odimall.order.processor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class OrderProcessor {

    private static final Logger logger = LoggerFactory.getLogger(OrderProcessor.class);

    private final AtomicLong ordersProcessed = new AtomicLong(0);
    private final AtomicLong totalItemsProcessed = new AtomicLong(0);

    private static final BigDecimal TAX_RATE = new BigDecimal("0.08");
    private static final BigDecimal FREE_SHIPPING_THRESHOLD = new BigDecimal("50.00");
    private static final BigDecimal FLAT_SHIPPING_COST = new BigDecimal("5.99");

    /**
     * Validates items, calculates totals/tax/shipping, builds a complete order summary.
     * This is the primary method intended for Odigos instrumentation.
     */
    public Map<String, Object> processOrder(String orderId, String sessionId, List<Map<String, Object>> items) {
        long startNanos = System.nanoTime();
        Instant processedAt = Instant.now();

        logger.info("Processing order={} session={} itemCount={}", orderId, sessionId, items.size());

        List<Map<String, Object>> validatedItems = validateAndEnrichItems(items);

        BigDecimal subtotal = BigDecimal.ZERO;
        int totalQuantity = 0;
        for (Map<String, Object> item : validatedItems) {
            BigDecimal lineTotal = (BigDecimal) item.get("lineTotal");
            subtotal = subtotal.add(lineTotal);
            totalQuantity += ((Number) item.get("quantity")).intValue();
        }

        BigDecimal tax = subtotal.multiply(TAX_RATE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal shipping = subtotal.compareTo(FREE_SHIPPING_THRESHOLD) >= 0
                ? BigDecimal.ZERO
                : FLAT_SHIPPING_COST;
        BigDecimal totalAmount = subtotal.add(tax).add(shipping);

        long orderNumber = ordersProcessed.incrementAndGet();
        totalItemsProcessed.addAndGet(totalQuantity);

        long elapsedNanos = System.nanoTime() - startNanos;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("orderId", orderId);
        summary.put("sessionId", sessionId);
        summary.put("processedAt", processedAt.toString());
        summary.put("itemCount", validatedItems.size());
        summary.put("totalQuantity", totalQuantity);
        summary.put("subtotal", subtotal.setScale(2, RoundingMode.HALF_UP));
        summary.put("tax", tax);
        summary.put("shippingCost", shipping);
        summary.put("totalAmount", totalAmount);
        summary.put("items", validatedItems);
        summary.put("processingTimeUs", elapsedNanos / 1000.0);
        summary.put("lifetimeOrdersProcessed", orderNumber);
        summary.put("lifetimeItemsProcessed", totalItemsProcessed.get());

        logger.info("Order processed: id={} total={} items={} processingUs={}",
                orderId, totalAmount, totalQuantity, String.format("%.1f", elapsedNanos / 1000.0));

        return summary;
    }

    private List<Map<String, Object>> validateAndEnrichItems(List<Map<String, Object>> items) {
        List<Map<String, Object>> validated = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Map<String, Object> enriched = new LinkedHashMap<>(item);

            Number priceNum = (Number) item.get("price");
            Number quantityNum = (Number) item.get("quantity");

            if (priceNum == null) {
                logger.warn("Missing price for item {}, defaulting to 0", item.get("productId"));
                priceNum = BigDecimal.ZERO;
            }
            if (quantityNum == null) {
                logger.warn("Missing quantity for item {}, defaulting to 1", item.get("productId"));
                quantityNum = 1;
            }

            BigDecimal price = new BigDecimal(priceNum.toString()).setScale(2, RoundingMode.HALF_UP);
            int quantity = quantityNum.intValue();

            if (quantity <= 0) {
                logger.warn("Skipping item with invalid quantity: {}", item);
                continue;
            }
            if (price.compareTo(BigDecimal.ZERO) <= 0) {
                logger.warn("Skipping item with invalid price: {}", item);
                continue;
            }

            BigDecimal lineTotal = price.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);

            enriched.put("price", price);
            enriched.put("quantity", quantity);
            enriched.put("lineTotal", lineTotal);
            validated.add(enriched);
        }
        return validated;
    }
}
