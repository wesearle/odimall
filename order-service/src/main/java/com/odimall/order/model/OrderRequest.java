package com.odimall.order.model;

import java.math.BigDecimal;
import java.util.List;

public class OrderRequest {

    private String sessionId;
    private List<OrderItem> items;
    private ShippingInfo shipping;

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public List<OrderItem> getItems() { return items; }
    public void setItems(List<OrderItem> items) { this.items = items; }

    public ShippingInfo getShipping() { return shipping; }
    public void setShipping(ShippingInfo shipping) { this.shipping = shipping; }

    public static class OrderItem {
        private Long productId;
        private Integer quantity;
        private BigDecimal price;
        private String name;

        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }

        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }

        public BigDecimal getPrice() { return price; }
        public void setPrice(BigDecimal price) { this.price = price; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    public static class ShippingInfo {
        private String name;
        private String address;
        private String city;
        private String state;
        private String zip;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }

        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }

        public String getState() { return state; }
        public void setState(String state) { this.state = state; }

        public String getZip() { return zip; }
        public void setZip(String zip) { this.zip = zip; }
    }
}
