package com.orderflow.inventory.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

import java.time.Instant;

/**
 * Prevents duplicate processing of the same OrderCreatedEvent.
 *
 * Table key design:
 *   PK = "PROCESSED#<orderId>"
 *
 * TTL is set to 48 h so DynamoDB auto-expires stale records and the table
 * doesn't grow unboundedly in production.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class InventoryIdempotencyRecord {

    private String  pk;           // "PROCESSED#<orderId>"
    private String  orderId;
    private String  status;       // "RESERVED" | "FAILED"
    private Instant processedAt;
    private Long    ttl;          // epoch seconds — 48 h from processedAt

    @DynamoDbPartitionKey
    @DynamoDbAttribute("PK")
    public String getPk() { return pk; }

    public void setPk(String pk) { this.pk = pk; }
}
