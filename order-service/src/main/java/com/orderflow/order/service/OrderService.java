package com.orderflow.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.common.events.OrderCreatedEvent;
import com.orderflow.order.dto.CreateOrderRequest;
import com.orderflow.order.dto.CreateOrderResponse;
import com.orderflow.order.exception.OrderNotFoundException;
import com.orderflow.order.model.IdempotencyRecord;
import com.orderflow.order.model.Order;
import com.orderflow.order.model.OutboxRecord;
import com.orderflow.order.repository.IdempotencyRepository;
import com.orderflow.order.repository.OrderRepository;
import com.orderflow.order.repository.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactPutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactWriteItemsEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Core business logic for order creation.
 *
 * <p>Idempotency strategy: attempt the DynamoDB TransactWriteItems unconditionally.
 * The idempotency-key PutItem carries {@code attribute_not_exists(PK)}, so if the key
 * already exists the transaction throws {@link TransactionCanceledException} and we
 * fall back to reading and returning the cached response. This guarantees:
 * <ul>
 *   <li>Exactly-once order creation even under duplicate HTTP retries</li>
 *   <li>Atomic creation of order + outbox record + idempotency key</li>
 * </ul>
 */
@Slf4j
@Service
public class OrderService {

    private final DynamoDbEnhancedClient enhancedClient;
    private final OrderRepository orderRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final Counter ordersCreatedCounter;

    public OrderService(DynamoDbEnhancedClient enhancedClient,
                        OrderRepository orderRepository,
                        IdempotencyRepository idempotencyRepository,
                        OutboxRepository outboxRepository,
                        ObjectMapper objectMapper,
                        MeterRegistry meterRegistry) {
        this.enhancedClient = enhancedClient;
        this.orderRepository = orderRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.ordersCreatedCounter = Counter.builder("orders.created")
                .description("Total number of orders successfully created")
                .register(meterRegistry);
    }

    public CreateOrderResponse createOrder(String idempotencyKey, CreateOrderRequest request) {
        String orderId = UUID.randomUUID().toString();
        MDC.put("orderId", orderId);
        MDC.put("idempotencyKey", idempotencyKey);
        try {
            Order order = buildOrder(orderId, request);
            OutboxRecord outboxRecord = buildOutboxRecord(order);
            IdempotencyRecord idempotencyRecord = buildIdempotencyRecord(idempotencyKey, orderId, order);

            try {
                executeAtomicWrite(order, outboxRecord, idempotencyRecord);
                log.info("Order created: orderId={}, customerId={}, totalAmount={}",
                        orderId, request.customerId(), order.getTotalAmount());
                ordersCreatedCounter.increment();
                return toResponse(order);
            } catch (TransactionCanceledException e) {
                // Condition on idempotency key fired → duplicate request
                log.info("Duplicate idempotency key; returning cached response: key={}", idempotencyKey);
                return idempotencyRepository.findByKey(idempotencyKey)
                        .map(rec -> parseCachedResponse(rec.getCachedResponse()))
                        .orElseThrow(() -> new IllegalStateException(
                                "Transaction cancelled but cached response not found for key: " + idempotencyKey));
            }
        } finally {
            MDC.remove("orderId");
            MDC.remove("idempotencyKey");
        }
    }

    public Order getOrder(String orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private Order buildOrder(String orderId, CreateOrderRequest request) {
        BigDecimal total = request.items().stream()
                .map(i -> i.unitPrice().multiply(BigDecimal.valueOf(i.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Order.OrderItem> items = request.items().stream()
                .map(i -> Order.OrderItem.builder()
                        .skuId(i.skuId())
                        .productName(i.productName())
                        .quantity(i.quantity())
                        .unitPrice(i.unitPrice())
                        .build())
                .toList();

        Instant now = Instant.now();
        return Order.builder()
                .pk("ORDER#" + orderId)
                .sk("METADATA")
                .orderId(orderId)
                .customerId(request.customerId())
                .items(items)
                .totalAmount(total)
                .status("PENDING")
                .createdAt(now)
                .updatedAt(now)
                .version(0L)
                .build();
    }

    private OutboxRecord buildOutboxRecord(Order order) {
        String outboxId = UUID.randomUUID().toString();

        // Build the domain event that will be published to Kafka
        OrderCreatedEvent event = new OrderCreatedEvent(
                order.getOrderId(),
                order.getCustomerId(),
                order.getItems().stream()
                        .map(i -> new OrderCreatedEvent.OrderItem(
                                i.getSkuId(), i.getProductName(),
                                i.getQuantity(), i.getUnitPrice()))
                        .toList(),
                order.getTotalAmount(),
                order.getCreatedAt());

        return OutboxRecord.builder()
                .pk("OUTBOX#" + outboxId)
                .outboxId(outboxId)
                .aggregateId(order.getOrderId())
                .eventType("OrderCreatedEvent")
                .payload(toJson(event))
                .status("PENDING")
                .createdAt(Instant.now())
                .retryCount(0)
                .build();
    }

    private IdempotencyRecord buildIdempotencyRecord(String idempotencyKey,
                                                      String orderId,
                                                      Order order) {
        return IdempotencyRecord.builder()
                .pk("IDEMPOTENCY#" + idempotencyKey)
                .idempotencyKey(idempotencyKey)
                .orderId(orderId)
                .cachedResponse(toJson(toResponse(order)))
                .createdAt(Instant.now())
                .ttl(Instant.now().plus(24, ChronoUnit.HOURS).getEpochSecond())
                .build();
    }

    /**
     * Atomic 3-way write: order + outbox record + idempotency key.
     * The {@code attribute_not_exists(PK)} condition on the idempotency item
     * causes the entire transaction to be cancelled if the key was already used.
     */
    private void executeAtomicWrite(Order order,
                                     OutboxRecord outboxRecord,
                                     IdempotencyRecord idempotencyRecord) {
        enhancedClient.transactWriteItems(TransactWriteItemsEnhancedRequest.builder()
                .addPutItem(orderRepository.table(),
                        TransactPutItemEnhancedRequest.builder(Order.class)
                                .item(order)
                                .build())
                .addPutItem(outboxRepository.table(),
                        TransactPutItemEnhancedRequest.builder(OutboxRecord.class)
                                .item(outboxRecord)
                                .build())
                .addPutItem(idempotencyRepository.table(),
                        TransactPutItemEnhancedRequest.builder(IdempotencyRecord.class)
                                .item(idempotencyRecord)
                                .conditionExpression(Expression.builder()
                                        .expression("attribute_not_exists(PK)")
                                        .build())
                                .build())
                .build());
    }

    private CreateOrderResponse toResponse(Order order) {
        return new CreateOrderResponse(
                order.getOrderId(), order.getStatus(),
                order.getTotalAmount(), order.getCreatedAt());
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialise object to JSON", e);
        }
    }

    private CreateOrderResponse parseCachedResponse(String json) {
        try {
            return objectMapper.readValue(json, CreateOrderResponse.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse cached response JSON", e);
        }
    }
}
