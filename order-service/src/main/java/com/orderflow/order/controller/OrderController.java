package com.orderflow.order.controller;

import com.orderflow.order.dto.CreateOrderRequest;
import com.orderflow.order.dto.CreateOrderResponse;
import com.orderflow.order.model.Order;
import com.orderflow.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Order management endpoints")
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @Operation(
            summary = "Create a new order",
            description = "Creates an order atomically with idempotency guarantee. " +
                    "Requires the Idempotency-Key header; duplicate requests return the cached response."
    )
    public ResponseEntity<CreateOrderResponse> createOrder(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {

        MDC.put("correlationId", idempotencyKey);
        try {
            log.info("POST /orders: customerId={}, items={}",
                    request.customerId(), request.items().size());
            CreateOrderResponse response = orderService.createOrder(idempotencyKey, request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } finally {
            MDC.remove("correlationId");
        }
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Get order by ID")
    public ResponseEntity<Order> getOrder(@PathVariable String orderId) {
        MDC.put("orderId", orderId);
        try {
            return ResponseEntity.ok(orderService.getOrder(orderId));
        } finally {
            MDC.remove("orderId");
        }
    }
}
