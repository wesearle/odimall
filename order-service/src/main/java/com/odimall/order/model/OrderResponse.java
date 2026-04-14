package com.odimall.order.model;

import java.math.BigDecimal;

public class OrderResponse {

    private String orderId;
    private String status;
    private BigDecimal totalAmount;
    private BigDecimal shippingCost;

    public OrderResponse() {}

    public OrderResponse(String orderId, String status, BigDecimal totalAmount, BigDecimal shippingCost) {
        this.orderId = orderId;
        this.status = status;
        this.totalAmount = totalAmount;
        this.shippingCost = shippingCost;
    }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public BigDecimal getShippingCost() { return shippingCost; }
    public void setShippingCost(BigDecimal shippingCost) { this.shippingCost = shippingCost; }
}
