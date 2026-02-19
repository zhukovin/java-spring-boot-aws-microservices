package com.orderflow.inventory.consumer;

import com.orderflow.common.events.OrderCreatedEvent;
import com.orderflow.inventory.service.InventoryService;
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
 * Consumes {@code orders.events} and triggers inventory reservation.
 *
 * <p><b>Retry chain</b> (non-blocking — does not stall the partition):
 * <pre>
 *   orders.events
 *     → orders.events-retry-0  (1 s delay)
 *     → orders.events-retry-1  (2 s delay, jitter)
 *     → orders.events.dlq      (after 3 total attempts)
 * </pre>
 *
 * Interview note: {@code @RetryableTopic} retries in a separate topic so the
 * main partition is never blocked, unlike the old {@code SeekToCurrentErrorHandler}
 * which would hold up the entire consumer group.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCreatedConsumer {

    private final InventoryService inventoryService;

    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 1_000, multiplier = 2, random = true),
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            retryTopicSuffix = "-retry",
            dltTopicSuffix = ".dlq"
    )
    @KafkaListener(
            topics = "${app.kafka.topics.orders-events}",
            groupId = "inventory-service"
    )
    public void consume(OrderCreatedEvent event,
                        @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        log.info("Received OrderCreatedEvent: orderId={}, topic={}",
                event.orderId(), topic);
        inventoryService.reserveInventory(event);
    }

    /**
     * Called after all retry attempts are exhausted.
     * In production: emit a PagerDuty / SNS alert and write to a dead-letter store
     * for manual reprocessing.
     */
    @DltHandler
    public void handleDlt(OrderCreatedEvent event,
                          @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        log.error("DLT: exhausted retries for orderId={}, topic={}. Manual intervention required.",
                event.orderId(), topic);
        // TODO: emit metric / alert
    }
}
