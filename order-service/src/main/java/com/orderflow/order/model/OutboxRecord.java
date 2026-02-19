package com.orderflow.order.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;

import java.time.Instant;

/**
 * DynamoDB table: order_outbox
 *
 * PK = "OUTBOX#<outboxId>"
 * GSI_PendingOutbox: PK = status, SK = createdAt
 * Polled by OutboxPublisherService every 1 s; status transitions PENDING → PUBLISHED.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class OutboxRecord {

    private String pk;
    private String outboxId;
    private String aggregateId;   // orderId
    private String eventType;
    private String payload;       // JSON event body
    private String status;        // PENDING | PUBLISHED
    private Instant createdAt;
    private Instant publishedAt;
    private int retryCount;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("PK")
    public String getPk() { return pk; }

    @DynamoDbSecondaryPartitionKey(indexNames = "GSI_PendingOutbox")
    @DynamoDbAttribute("status")
    public String getStatus() { return status; }

    @DynamoDbSecondarySortKey(indexNames = "GSI_PendingOutbox")
    @DynamoDbAttribute("createdAt")
    public Instant getCreatedAt() { return createdAt; }
}
