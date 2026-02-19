package com.orderflow.common.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderCreatedEvent(
        String orderId,
        String customerId,
        List<OrderItem> items,
        BigDecimal totalAmount,
        Instant createdAt
) {
    public record OrderItem(
            String skuId,
            String productName,
            int quantity,
            BigDecimal unitPrice
    ) {}
}
