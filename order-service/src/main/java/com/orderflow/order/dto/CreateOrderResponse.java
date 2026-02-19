package com.orderflow.order.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record CreateOrderResponse(
        String orderId,
        String status,
        BigDecimal totalAmount,
        Instant createdAt
) {}
