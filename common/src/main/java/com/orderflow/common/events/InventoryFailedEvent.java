package com.orderflow.common.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record InventoryFailedEvent(
        String orderId,
        String customerId,
        String reason,
        Instant failedAt
) {}
