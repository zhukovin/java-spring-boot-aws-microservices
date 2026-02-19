package com.orderflow.payment.consumer;

import com.orderflow.common.events.InventoryReservedEvent;
import com.orderflow.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code inventory.events} (InventoryReservedEvent only) and
 * triggers payment processing.
 *
 * <p><b>Retry chain</b>:
 * <pre>
 *   inventory.events
 *     → inventory.events-retry-0  (1 s delay)
 *     → inventory.events-retry-1  (2 s delay, jitter)
 *     → inventory.events.dlq      (after 3 total attempts)
 * </pre>
 *
 * <p>Note: {@code InventoryFailedEvent} messages on the same topic are
 * ignored — the deserializer is configured to expect {@code InventoryReservedEvent}
 * and the payment-service has no action to take on failed inventory reservations.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryReservedConsumer {

    private final PaymentService paymentService;

    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 1_000, multiplier = 2, random = true),
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            retryTopicSuffix = "-retry",
            dltTopicSuffix = ".dlq"
    )
    @KafkaListener(
            topics = "${app.kafka.topics.inventory-events}",
            groupId = "payment-service"
    )
    public void consume(InventoryReservedEvent event,
                        @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        log.info("Received InventoryReservedEvent: orderId={}, topic={}",
                event.orderId(), topic);
        paymentService.processPayment(event);
    }

    /**
     * Called after all retry attempts are exhausted.
     * In production: emit a PagerDuty / SNS alert for manual intervention.
     */
    @DltHandler
    public void handleDlt(InventoryReservedEvent event,
                          @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        log.error("DLT: exhausted retries for orderId={}, topic={}. Manual intervention required.",
                event.orderId(), topic);
        // TODO: emit metric / alert
    }
}
