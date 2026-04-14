package com.odimall.order.controller;

import com.odimall.order.model.OrderRequest;
import com.odimall.order.model.OrderResponse;
import com.odimall.order.processor.OrderProcessor;
import com.odimall.order.trace.TraceContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private static final Logger logger = LoggerFactory.getLogger(OrderController.class);
    private static final long GLACIER_SLEEPING_BAG_ID = 3L;

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final RestTemplate restTemplate;
    private final OrderProcessor orderProcessor;

    @Value("${services.product-url}")
    private String productServiceUrl;

    @Value("${services.payment-url}")
    private String paymentServiceUrl;

    @Value("${services.shipping-url}")
    private String shippingServiceUrl;

    @Value("${services.inventory-url}")
    private String inventoryServiceUrl;

    public OrderController(JdbcTemplate jdbcTemplate, DataSource dataSource,
                           RestTemplate restTemplate, OrderProcessor orderProcessor) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
        this.restTemplate = restTemplate;
        this.orderProcessor = orderProcessor;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@RequestBody OrderRequest request) {
        String orderId = UUID.randomUUID().toString();

        logger.info("Creating order={} session={} items={}", orderId, request.getSessionId(), request.getItems().size());

        // Fetch verified prices from product-service (triggers SQL context propagation via product-service → MySQL)
        List<Map<String, Object>> itemMaps = new ArrayList<>();
        for (OrderRequest.OrderItem item : request.getItems()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("productId", item.getProductId());
            map.put("quantity", item.getQuantity());

            BigDecimal verifiedPrice = null;
            String verifiedName = null;
            try {
                String url = productServiceUrl + "/products/" + item.getProductId();
                ResponseEntity<Map> productResponse = restTemplate.getForEntity(url, Map.class);
                Map productBody = productResponse.getBody();
                if (productBody != null) {
                    if (productBody.get("price") != null) {
                        verifiedPrice = new BigDecimal(productBody.get("price").toString());
                    }
                    if (productBody.get("name") != null) {
                        verifiedName = productBody.get("name").toString();
                    }
                }
                logger.info("Verified product={} price={} name={}", item.getProductId(), verifiedPrice, verifiedName);
            } catch (Exception e) {
                logger.warn("Could not verify product={} from product-service: {}, using client values",
                        item.getProductId(), e.getMessage());
            }

            map.put("price", verifiedPrice != null ? verifiedPrice : item.getPrice());
            map.put("name", verifiedName != null ? verifiedName : item.getName());
            itemMaps.add(map);
        }

        Map<String, Object> processingResult = orderProcessor.processOrder(orderId, request.getSessionId(), itemMaps);

        BigDecimal subtotal = (BigDecimal) processingResult.get("subtotal");
        BigDecimal shippingCost;

        // Call payment service
        try {
            Map<String, Object> paymentRequest = Map.of("orderId", orderId, "amount", subtotal);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.postForEntity(paymentServiceUrl + "/payments/process",
                    new HttpEntity<>(paymentRequest, headers), Map.class);
            logger.info("Payment processed for order={}", orderId);
        } catch (Exception e) {
            logger.error("Payment service call failed for order={}: {}", orderId, e.getMessage());
        }

        // Call shipping service
        try {
            Map<String, Object> shippingRequest = new HashMap<>();
            List<Map<String, Object>> shippingItems = new ArrayList<>();
            for (OrderRequest.OrderItem item : request.getItems()) {
                shippingItems.add(Map.of("productId", item.getProductId(), "quantity", item.getQuantity()));
            }
            shippingRequest.put("items", shippingItems);
            shippingRequest.put("destination", Map.of(
                    "state", request.getShipping().getState(),
                    "zip", request.getShipping().getZip()
            ));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<Map> shippingResponse = restTemplate.postForEntity(
                    shippingServiceUrl + "/shipping/calculate",
                    new HttpEntity<>(shippingRequest, headers), Map.class);

            Map body = shippingResponse.getBody();
            shippingCost = body != null && body.get("cost") != null
                    ? new BigDecimal(body.get("cost").toString()).setScale(2, RoundingMode.HALF_UP)
                    : (BigDecimal) processingResult.get("shippingCost");
            logger.info("Shipping calculated for order={}: cost={}", orderId, shippingCost);
        } catch (Exception e) {
            logger.warn("Shipping service call failed for order={}: {}, using default", orderId, e.getMessage());
            shippingCost = (BigDecimal) processingResult.get("shippingCost");
        }

        // Call inventory service
        try {
            List<Map<String, Object>> reserveItems = new ArrayList<>();
            for (OrderRequest.OrderItem item : request.getItems()) {
                reserveItems.add(Map.of("productId", item.getProductId(), "quantity", item.getQuantity()));
            }
            Map<String, Object> inventoryRequest = Map.of("orderId", orderId, "items", reserveItems);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.postForEntity(inventoryServiceUrl + "/inventory/reserve",
                    new HttpEntity<>(inventoryRequest, headers), Map.class);
            logger.info("Inventory reserved for order={}", orderId);
        } catch (Exception e) {
            logger.error("Inventory service call failed for order={}: {}", orderId, e.getMessage());
        }

        BigDecimal totalAmount = subtotal.add(shippingCost).setScale(2, RoundingMode.HALF_UP);

        // Check for Glacier Sleeping Bag (product_id=3) - trigger DB lock pattern
        boolean containsGlacierBag = request.getItems().stream()
                .anyMatch(item -> item.getProductId() != null && item.getProductId() == GLACIER_SLEEPING_BAG_ID);

        if (containsGlacierBag) {
            triggerDbLockPattern();
        }

        // Save order to database
        saveOrder(orderId, request, totalAmount, shippingCost);
        saveOrderItems(orderId, request.getItems());

        logger.info("Order created: id={} total={} shipping={}", orderId, totalAmount, shippingCost);

        OrderResponse response = new OrderResponse(orderId, "confirmed", totalAmount, shippingCost);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    private void saveOrder(String orderId, OrderRequest request, BigDecimal totalAmount, BigDecimal shippingCost) {
        String sql = TraceContextHolder.appendComment(
                "INSERT INTO orders (id, session_id, total_amount, shipping_cost, shipping_name, " +
                "shipping_address, shipping_city, shipping_state, shipping_zip, status, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'confirmed', NOW())");

        jdbcTemplate.update(sql,
                orderId, request.getSessionId(), totalAmount, shippingCost,
                request.getShipping().getName(), request.getShipping().getAddress(),
                request.getShipping().getCity(), request.getShipping().getState(),
                request.getShipping().getZip());
    }

    private void saveOrderItems(String orderId, List<OrderRequest.OrderItem> items) {
        String sql = TraceContextHolder.appendComment(
                "INSERT INTO order_items (order_id, product_id, product_name, price, quantity) VALUES (?, ?, ?, ?, ?)");
        for (OrderRequest.OrderItem item : items) {
            jdbcTemplate.update(sql, orderId, item.getProductId(), item.getName(), item.getPrice(), item.getQuantity());
        }
    }

    /**
     * Triggers a deliberate DB lock contention pattern for the Glacier Sleeping Bag (product_id=3).
     * Connection 1 acquires a FOR UPDATE lock, then Connection 2 attempts an update that blocks
     * until the lock wait timeout fires. The timeout exception is caught so the order still completes.
     */
    private void triggerDbLockPattern() {
        logger.warn("Glacier Sleeping Bag detected - triggering DB lock pattern for product_id={}", GLACIER_SLEEPING_BAG_ID);

        Connection lockHolder = null;
        Connection lockWaiter = null;

        try {
            // Connection 1: acquire exclusive row lock
            lockHolder = dataSource.getConnection();
            lockHolder.setAutoCommit(false);
            try (PreparedStatement ps = lockHolder.prepareStatement(
                    TraceContextHolder.appendComment("SELECT * FROM inventory WHERE product_id = ? FOR UPDATE"))) {
                ps.setLong(1, GLACIER_SLEEPING_BAG_ID);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        logger.info("Lock acquired on inventory row for product_id={}, quantity={}",
                                GLACIER_SLEEPING_BAG_ID, rs.getInt("quantity"));
                    }
                }
            }

            // Connection 2: attempt update that will block on the lock
            lockWaiter = dataSource.getConnection();
            lockWaiter.setAutoCommit(false);
            try (Statement stmt = lockWaiter.createStatement()) {
                stmt.execute(TraceContextHolder.appendComment("SET innodb_lock_wait_timeout = 5"));
            }

            try (PreparedStatement ps = lockWaiter.prepareStatement(
                    TraceContextHolder.appendComment("UPDATE inventory SET quantity = quantity - 1 WHERE product_id = ?"))) {
                ps.setLong(1, GLACIER_SLEEPING_BAG_ID);
                ps.executeUpdate();
                lockWaiter.commit();
            }

        } catch (Exception e) {
            logger.warn("DB lock timeout triggered for Glacier Sleeping Bag (product_id={}): {}",
                    GLACIER_SLEEPING_BAG_ID, e.getMessage());
        } finally {
            closeConnection(lockWaiter);
            try {
                if (lockHolder != null) {
                    lockHolder.rollback();
                }
            } catch (Exception e) {
                logger.debug("Error rolling back lock holder connection", e);
            }
            closeConnection(lockHolder);
        }
    }

    private void closeConnection(Connection conn) {
        if (conn != null) {
            try {
                conn.close();
            } catch (Exception e) {
                logger.debug("Error closing connection", e);
            }
        }
    }
}
