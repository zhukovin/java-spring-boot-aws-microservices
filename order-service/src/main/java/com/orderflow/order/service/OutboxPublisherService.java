package com.orderflow.order.service;

import com.orderflow.order.model.OutboxRecord;
import com.orderflow.order.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Transactional Outbox Poller.
 *
 * <p>Every {@code app.outbox.poll-interval-ms} milliseconds (default 1 s) this
 * service reads up to {@code app.outbox.batch-size} PENDING outbox records from
 * the GSI_PendingOutbox index and publishes each payload to Kafka synchronously.
 *
 * <p>Only after the Kafka broker acknowledges the send (via {@code .get(5, SECONDS)})
 * is the outbox record transitioned to PUBLISHED, guaranteeing at-least-once delivery
 * without dual-write risk.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxPublisherService {

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${app.kafka.topics.orders-events:orders.events}")
    private String ordersEventsTopic;

    @Value("${app.outbox.batch-size:50}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
    public void publishPendingOutboxMessages() {
        List<OutboxRecord> pending = outboxRepository.findPending(batchSize);
        if (pending.isEmpty()) {
            return;
        }

        log.debug("Outbox poll: {} PENDING record(s) found", pending.size());

        for (OutboxRecord record : pending) {
            MDC.put("orderId", record.getAggregateId());
            MDC.put("outboxId", record.getOutboxId());
            try {
                // Synchronous send — block until broker acks or timeout
                kafkaTemplate.send(ordersEventsTopic, record.getAggregateId(), record.getPayload())
                        .get(5, TimeUnit.SECONDS);

                outboxRepository.markPublished(record.getOutboxId());

                log.info("Outbox record published: outboxId={}, eventType={}, orderId={}",
                        record.getOutboxId(), record.getEventType(), record.getAggregateId());
            } catch (Exception e) {
                log.error("Failed to publish outbox record: outboxId={}, error={}",
                        record.getOutboxId(), e.getMessage(), e);
                // Leave as PENDING; will be retried on the next poll cycle
            } finally {
                MDC.remove("orderId");
                MDC.remove("outboxId");
            }
        }
    }
}
