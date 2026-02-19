package com.orderflow.common.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentSucceededEvent(
        String orderId,
        String customerId,
        BigDecimal amount,
        String transactionId,
        Instant processedAt
) {}
