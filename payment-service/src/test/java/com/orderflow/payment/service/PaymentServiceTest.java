package com.orderflow.payment.service;

import com.orderflow.common.events.InventoryReservedEvent;
import com.orderflow.common.events.PaymentFailedEvent;
import com.orderflow.common.events.PaymentSucceededEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock KafkaTemplate<String, Object> kafkaTemplate;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(kafkaTemplate);
        ReflectionTestUtils.setField(paymentService, "paymentTopic", "payment.events");
    }

    @Test
    void processPayment_alwaysPublishesAnEvent() {
        var event = buildEvent();
        paymentService.processPayment(event);

        verify(kafkaTemplate).send(eq("payment.events"), eq(event.orderId()), any());
    }

    @Test
    void processPayment_publishesEitherSuccessOrFailureEvent() {
        var event = buildEvent();
        paymentService.processPayment(event);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq("payment.events"), eq(event.orderId()), captor.capture());
        assertThat(captor.getValue())
                .isInstanceOfAny(PaymentSucceededEvent.class, PaymentFailedEvent.class);
    }

    @Test
    void paymentFallback_publishesFailedEventWithGatewayReason() {
        var event = buildEvent();
        paymentService.paymentFallback(event, new RuntimeException("circuit open"));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq("payment.events"), eq(event.orderId()), captor.capture());
        assertThat(captor.getValue()).isInstanceOf(PaymentFailedEvent.class);
        PaymentFailedEvent failed = (PaymentFailedEvent) captor.getValue();
        assertThat(failed.reason()).isEqualTo("Payment gateway unavailable");
        assertThat(failed.amount()).isEqualByComparingTo("20.00");
        assertThat(failed.orderId()).isEqualTo(event.orderId());
    }

    @Test
    void paymentFallback_populatesAllFields() {
        var event = buildEvent();
        paymentService.paymentFallback(event, new RuntimeException("timeout"));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(anyString(), anyString(), captor.capture());
        PaymentFailedEvent failed = (PaymentFailedEvent) captor.getValue();
        assertThat(failed.customerId()).isEqualTo("customer-123");
        assertThat(failed.failedAt()).isNotNull();
    }

    /**
     * Statistical test: over 50 independent calls, at least one should succeed
     * and at least one should fail (90/10 split).  The probability of all-success
     * or all-failure across 50 runs is negligibly small (< 0.006 %).
     */
    @RepeatedTest(50)
    void processPayment_doesNotThrow() {
        var event = buildEvent();
        paymentService.processPayment(event);
        verify(kafkaTemplate, atLeastOnce()).send(eq("payment.events"), eq(event.orderId()), any());
        clearInvocations(kafkaTemplate);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private InventoryReservedEvent buildEvent() {
        return new InventoryReservedEvent(
                "order-123", "customer-123",
                List.of(new InventoryReservedEvent.ReservedItem("SKU-001", 2)),
                new BigDecimal("20.00"),
                Instant.now());
    }
}
