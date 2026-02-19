package com.orderflow.order.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.order.dto.CreateOrderRequest;
import com.orderflow.order.dto.CreateOrderResponse;
import com.orderflow.order.exception.GlobalExceptionHandler;
import com.orderflow.order.exception.OrderNotFoundException;
import com.orderflow.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrderController.class)
@Import(GlobalExceptionHandler.class)
class OrderControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean  OrderService orderService;

    @Test
    void createOrder_withValidRequest_returns201() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String orderId = UUID.randomUUID().toString();

        var request = new CreateOrderRequest(
                "customer-123",
                List.of(new CreateOrderRequest.OrderItemRequest(
                        "SKU-001", "Widget Pro", 2, new BigDecimal("29.99"))));

        var response = new CreateOrderResponse(
                orderId, "PENDING", new BigDecimal("59.98"), Instant.now());

        when(orderService.createOrder(eq(idempotencyKey), any())).thenReturn(response);

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", idempotencyKey)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(59.98));
    }

    @Test
    void createOrder_withoutIdempotencyKey_returns400() throws Exception {
        var request = new CreateOrderRequest(
                "customer-123",
                List.of(new CreateOrderRequest.OrderItemRequest(
                        "SKU-001", "Widget Pro", 2, new BigDecimal("29.99"))));

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createOrder_withEmptyItems_returns400() throws Exception {
        var request = new CreateOrderRequest("customer-123", List.of());

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("items"));
    }

    @Test
    void createOrder_withMissingCustomerId_returns400() throws Exception {
        var request = new CreateOrderRequest(
                "",
                List.of(new CreateOrderRequest.OrderItemRequest(
                        "SKU-001", "Widget", 1, BigDecimal.ONE)));

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getOrder_whenNotFound_returns404() throws Exception {
        String orderId = UUID.randomUUID().toString();
        when(orderService.getOrder(orderId)).thenThrow(new OrderNotFoundException(orderId));

        mockMvc.perform(get("/orders/{orderId}", orderId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
