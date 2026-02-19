package com.orderflow.inventory.service;

import com.orderflow.common.events.InventoryFailedEvent;
import com.orderflow.common.events.InventoryReservedEvent;
import com.orderflow.common.events.OrderCreatedEvent;
import com.orderflow.inventory.model.InventoryIdempotencyRecord;
import com.orderflow.inventory.repository.InventoryIdempotencyRepository;
import com.orderflow.inventory.repository.InventoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock InventoryRepository            inventoryRepository;
    @Mock InventoryIdempotencyRepository idempotencyRepository;
    @Mock KafkaTemplate<String, Object>  kafkaTemplate;

    private InventoryService inventoryService;

    @BeforeEach
    void setUp() {
        inventoryService = new InventoryService(
                inventoryRepository, idempotencyRepository, kafkaTemplate);
        ReflectionTestUtils.setField(inventoryService, "inventoryTopic", "inventory.events");
    }

    @Test
    void reserveInventory_success_publishesReservedEventAndRecordsIdempotency() {
        var event = buildEvent();
        when(idempotencyRepository.exists(event.orderId())).thenReturn(false);

        inventoryService.reserveInventory(event);

        verify(inventoryRepository).reserveItemsTransactionally(event.items());

        ArgumentCaptor<Object> msgCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq("inventory.events"), eq(event.orderId()), msgCaptor.capture());
        assertThat(msgCaptor.getValue()).isInstanceOf(InventoryReservedEvent.class);

        ArgumentCaptor<InventoryIdempotencyRecord> recordCaptor =
                ArgumentCaptor.forClass(InventoryIdempotencyRecord.class);
        verify(idempotencyRepository).save(recordCaptor.capture());
        assertThat(recordCaptor.getValue().getStatus()).isEqualTo("RESERVED");
    }

    @Test
    void reserveInventory_insufficientStock_publishesFailedEventAndRecordsIdempotency() {
        var event = buildEvent();
        when(idempotencyRepository.exists(event.orderId())).thenReturn(false);
        doThrow(TransactionCanceledException.builder()
                        .message("ConditionalCheckFailed").build())
                .when(inventoryRepository).reserveItemsTransactionally(any());

        inventoryService.reserveInventory(event);

        ArgumentCaptor<Object> msgCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq("inventory.events"), eq(event.orderId()), msgCaptor.capture());
        assertThat(msgCaptor.getValue()).isInstanceOf(InventoryFailedEvent.class);

        ArgumentCaptor<InventoryIdempotencyRecord> recordCaptor =
                ArgumentCaptor.forClass(InventoryIdempotencyRecord.class);
        verify(idempotencyRepository).save(recordCaptor.capture());
        assertThat(recordCaptor.getValue().getStatus()).isEqualTo("FAILED");
    }

    @Test
    void reserveInventory_duplicateEvent_skipsProcessingEntirely() {
        var event = buildEvent();
        when(idempotencyRepository.exists(event.orderId())).thenReturn(true);

        inventoryService.reserveInventory(event);

        verify(inventoryRepository, never()).reserveItemsTransactionally(any());
        verify(kafkaTemplate, never()).send(any(), any(), any());
        verify(idempotencyRepository, never()).save(any());
    }

    @Test
    void reserveInventory_totalAmount_forwardedToReservedEvent() {
        var event = buildEvent();
        when(idempotencyRepository.exists(event.orderId())).thenReturn(false);

        inventoryService.reserveInventory(event);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(anyString(), anyString(), captor.capture());
        InventoryReservedEvent reserved = (InventoryReservedEvent) captor.getValue();
        assertThat(reserved.totalAmount()).isEqualByComparingTo("20.00");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private OrderCreatedEvent buildEvent() {
        return new OrderCreatedEvent(
                "order-123", "customer-123",
                List.of(new OrderCreatedEvent.OrderItem(
                        "SKU-001", "Widget", 2, new BigDecimal("10.00"))),
                new BigDecimal("20.00"),
                Instant.now());
    }
}
