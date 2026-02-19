package com.orderflow.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

public record CreateOrderRequest(

        @NotBlank(message = "customerId is required")
        String customerId,

        @NotEmpty(message = "items cannot be empty")
        @Valid
        List<OrderItemRequest> items

) {
    public record OrderItemRequest(

            @NotBlank(message = "skuId is required")
            String skuId,

            @NotBlank(message = "productName is required")
            String productName,

            @Min(value = 1, message = "quantity must be at least 1")
            int quantity,

            @NotNull(message = "unitPrice is required")
            @DecimalMin(value = "0.01", message = "unitPrice must be greater than zero")
            BigDecimal unitPrice

    ) {}
}
