package com.orderflow.order.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

import java.time.Instant;

/**
 * DynamoDB table: order_idempotency_keys
 *
 * PK = "IDEMPOTENCY#<idempotencyKey>"
 * TTL attribute: 24 h auto-expiry via DynamoDB TTL
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class IdempotencyRecord {

    private String pk;
    private String idempotencyKey;
    private String orderId;
    private String cachedResponse;   // JSON-serialised CreateOrderResponse
    private Instant createdAt;
    private Long ttl;                // epoch-seconds; DynamoDB TTL attribute

    @DynamoDbPartitionKey
    @DynamoDbAttribute("PK")
    public String getPk() { return pk; }
}
