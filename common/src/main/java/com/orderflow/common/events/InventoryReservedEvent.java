package com.orderflow.common.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record InventoryReservedEvent(
        String orderId,
        String customerId,
        List<ReservedItem> reservedItems,
        BigDecimal totalAmount,
        Instant reservedAt
) {
    public record ReservedItem(
            String skuId,
            int quantity
    ) {}
}
