package com.orderflow.payment.service;

import com.orderflow.common.events.InventoryReservedEvent;
import com.orderflow.common.events.PaymentFailedEvent;
import com.orderflow.common.events.PaymentSucceededEvent;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Random;
import java.util.UUID;

/**
 * Simulates payment processing with a configurable success rate.
 *
 * <p>In production this would call a real payment gateway (Stripe, Braintree, etc.).
 * The circuit breaker prevents cascade failures when the downstream gateway is
 * unavailable, and the fallback immediately publishes a {@link PaymentFailedEvent}
 * so the saga can compensate.
 *
 * <p>Interview note: the 90 % success rate is intentional — it demonstrates
 * the full saga failure path (InventoryReservedEvent → PaymentFailedEvent)
 * without needing an actual gateway integration.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final Random random = new Random();

    @Value("${app.kafka.topics.payment-events}")
    private String paymentTopic;

    @CircuitBreaker(name = "payment", fallbackMethod = "paymentFallback")
    public void processPayment(InventoryReservedEvent event) {
        MDC.put("orderId", event.orderId());
        try {
            log.info("Processing payment for orderId={}, amount={}",
                    event.orderId(), event.totalAmount());

            if (random.nextDouble() < 0.9) {
                String transactionId = UUID.randomUUID().toString();
                var successEvent = new PaymentSucceededEvent(
                        event.orderId(), event.customerId(),
                        event.totalAmount(), transactionId, Instant.now());
                kafkaTemplate.send(paymentTopic, event.orderId(), successEvent);
                log.info("Payment succeeded for orderId={}, txId={}", event.orderId(), transactionId);

            } else {
                var failedEvent = new PaymentFailedEvent(
                        event.orderId(), event.customerId(),
                        event.totalAmount(), "Payment declined by gateway simulation", Instant.now());
                kafkaTemplate.send(paymentTopic, event.orderId(), failedEvent);
                log.warn("Payment declined (simulation) for orderId={}", event.orderId());
            }
        } finally {
            MDC.remove("orderId");
        }
    }

    /**
     * Fallback when the circuit breaker is open (gateway is down).
     * Always publishes a failure event so downstream consumers can compensate.
     */
    void paymentFallback(InventoryReservedEvent event, Exception ex) {
        log.error("Payment circuit breaker open for orderId={}: {}",
                event.orderId(), ex.getMessage());
        var failedEvent = new PaymentFailedEvent(
                event.orderId(), event.customerId(),
                event.totalAmount(), "Payment gateway unavailable", Instant.now());
        kafkaTemplate.send(paymentTopic, event.orderId(), failedEvent);
    }
}
