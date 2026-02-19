package com.orderflow.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.orderflow.order.dto.CreateOrderRequest;
import com.orderflow.order.dto.CreateOrderResponse;
import com.orderflow.order.model.IdempotencyRecord;
import com.orderflow.order.repository.IdempotencyRepository;
import com.orderflow.order.repository.OrderRepository;
import com.orderflow.order.repository.OutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactWriteItemsEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock DynamoDbEnhancedClient enhancedClient;
    @Mock OrderRepository       orderRepository;
    @Mock IdempotencyRepository idempotencyRepository;
    @Mock OutboxRepository      outboxRepository;

    private OrderService orderService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        orderService = new OrderService(
                enhancedClient, orderRepository, idempotencyRepository,
                outboxRepository, objectMapper, new SimpleMeterRegistry());

        // Each repository.table() call returns a mock table (needed by executeAtomicWrite)
        when(orderRepository.table()).thenReturn(mock(DynamoDbTable.class));
        when(outboxRepository.table()).thenReturn(mock(DynamoDbTable.class));
        when(idempotencyRepository.table()).thenReturn(mock(DynamoDbTable.class));
    }

    @Test
    void createOrder_newRequest_createsOrderAndReturnsResponse() {
        var request = new CreateOrderRequest(
                "customer-123",
                List.of(new CreateOrderRequest.OrderItemRequest(
                        "SKU-001", "Widget", 2, new BigDecimal("10.00"))));

        CreateOrderResponse response = orderService.createOrder(UUID.randomUUID().toString(), request);

        assertThat(response.orderId()).isNotBlank();
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.totalAmount()).isEqualByComparingTo("20.00");
        assertThat(response.createdAt()).isNotNull();
        verify(enhancedClient).transactWriteItems(any(TransactWriteItemsEnhancedRequest.class));
    }

    @Test
    void createOrder_multipleItems_sumsTotalCorrectly() {
        var request = new CreateOrderRequest(
                "customer-123",
                List.of(
                        new CreateOrderRequest.OrderItemRequest("SKU-001", "Widget", 3, new BigDecimal("10.00")),
                        new CreateOrderRequest.OrderItemRequest("SKU-002", "Gadget", 2, new BigDecimal("25.00"))
                ));

        CreateOrderResponse response = orderService.createOrder(UUID.randomUUID().toString(), request);

        // 3 * 10 + 2 * 25 = 80
        assertThat(response.totalAmount()).isEqualByComparingTo("80.00");
    }

    @Test
    void createOrder_duplicateIdempotencyKey_returnsCachedResponse() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String existingOrderId = UUID.randomUUID().toString();

        var cachedResponse = new CreateOrderResponse(
                existingOrderId, "PENDING", new BigDecimal("10.00"), Instant.now());
        String cachedJson = objectMapper.writeValueAsString(cachedResponse);

        IdempotencyRecord existingRecord = IdempotencyRecord.builder()
                .pk("IDEMPOTENCY#" + idempotencyKey)
                .idempotencyKey(idempotencyKey)
                .orderId(existingOrderId)
                .cachedResponse(cachedJson)
                .build();

        // Transaction fails because idempotency key already exists
        doThrow(TransactionCanceledException.builder()
                .message("ConditionalCheckFailed").build())
                .when(enhancedClient)
                .transactWriteItems(any(TransactWriteItemsEnhancedRequest.class));

        when(idempotencyRepository.findByKey(idempotencyKey))
                .thenReturn(Optional.of(existingRecord));

        var request = new CreateOrderRequest(
                "customer-123",
                List.of(new CreateOrderRequest.OrderItemRequest(
                        "SKU-001", "Widget", 1, new BigDecimal("10.00"))));

        CreateOrderResponse response = orderService.createOrder(idempotencyKey, request);

        // Must return the CACHED response, not a new one
        assertThat(response.orderId()).isEqualTo(existingOrderId);
        verify(idempotencyRepository).findByKey(idempotencyKey);
    }
}
