package com.orderflow.inventory.service;

import com.orderflow.common.events.InventoryFailedEvent;
import com.orderflow.common.events.InventoryReservedEvent;
import com.orderflow.common.events.OrderCreatedEvent;
import com.orderflow.inventory.model.InventoryIdempotencyRecord;
import com.orderflow.inventory.repository.InventoryIdempotencyRepository;
import com.orderflow.inventory.repository.InventoryRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository           inventoryRepository;
    private final InventoryIdempotencyRepository idempotencyRepository;
    private final KafkaTemplate<String, Object>  kafkaTemplate;

    @Value("${app.kafka.topics.inventory-events}")
    private String inventoryTopic;

    /**
     * Reserves inventory for all items in the order atomically.
     *
     * <p>Idempotency: if the orderId has already been processed (record exists in
     * inventory_idempotency table) the method returns immediately without
     * side-effects, making the consumer safe to call multiple times.
     *
     * <p>Wrapped with {@code @CircuitBreaker} + {@code @Retry} so that transient
     * DynamoDB / Kafka failures are retried with exponential back-off, and
     * persistent failures open the circuit to prevent cascade overload.
     *
     * <p>{@link TransactionCanceledException} (insufficient stock) is caught
     * internally — it is NOT a retriable condition and is NOT re-thrown.
     */
    @CircuitBreaker(name = "inventory", fallbackMethod = "reserveFallback")
    @Retry(name = "inventory")
    public void reserveInventory(OrderCreatedEvent event) {
        MDC.put("orderId", event.orderId());
        try {
            // ── 1. Idempotency guard ──────────────────────────────────────────
            if (idempotencyRepository.exists(event.orderId())) {
                log.info("Duplicate event for orderId={}, skipping", event.orderId());
                return;
            }

            log.info("Reserving inventory for orderId={}, itemCount={}",
                    event.orderId(), event.items().size());

            // ── 2. Atomic stock reservation via TransactWriteItems ────────────
            try {
                inventoryRepository.reserveItemsTransactionally(event.items());
                log.info("Inventory reserved for orderId={}", event.orderId());
                publishReserved(event);
                recordIdempotency(event.orderId(), "RESERVED");

            } catch (TransactionCanceledException e) {
                // A SKU had availableQty < requested qty; no stock was modified
                log.warn("Insufficient inventory for orderId={}: {}", event.orderId(), e.getMessage());
                publishFailed(event, "Insufficient inventory for one or more items");
                recordIdempotency(event.orderId(), "FAILED");
            }

        } finally {
            MDC.remove("orderId");
        }
    }

    /**
     * Fallback invoked when the circuit breaker is open or a non-idempotent exception
     * propagates after all retries are exhausted.  Re-throws so that
     * {@code @RetryableTopic} can route the message to the retry / DLT chain.
     */
    void reserveFallback(OrderCreatedEvent event, Exception ex) {
        log.error("Inventory service unavailable for orderId={}: {}",
                event.orderId(), ex.getMessage());
        // Re-throw so the Kafka consumer marks the offset as failed and
        // @RetryableTopic schedules a retry delivery.
        throw new RuntimeException("Inventory service unavailable, will retry", ex);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private void publishReserved(OrderCreatedEvent event) {
        List<InventoryReservedEvent.ReservedItem> reservedItems = event.items().stream()
                .map(i -> new InventoryReservedEvent.ReservedItem(i.skuId(), i.quantity()))
                .collect(Collectors.toList());

        var reservedEvent = new InventoryReservedEvent(
                event.orderId(), event.customerId(),
                reservedItems, event.totalAmount(), Instant.now());

        kafkaTemplate.send(inventoryTopic, event.orderId(), reservedEvent);
        log.info("Published InventoryReservedEvent for orderId={}", event.orderId());
    }

    private void publishFailed(OrderCreatedEvent event, String reason) {
        var failedEvent = new InventoryFailedEvent(
                event.orderId(), event.customerId(), reason, Instant.now());
        kafkaTemplate.send(inventoryTopic, event.orderId(), failedEvent);
        log.warn("Published InventoryFailedEvent for orderId={}", event.orderId());
    }

    private void recordIdempotency(String orderId, String status) {
        long ttl = Instant.now().plusSeconds(48L * 3_600).getEpochSecond();
        idempotencyRepository.save(InventoryIdempotencyRecord.builder()
                .pk("PROCESSED#" + orderId)
                .orderId(orderId)
                .status(status)
                .processedAt(Instant.now())
                .ttl(ttl)
                .build());
    }
}
