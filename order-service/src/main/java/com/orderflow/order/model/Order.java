package com.orderflow.order.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * DynamoDB table: orders
 *
 * PK = "ORDER#<orderId>"   SK = "METADATA"
 * GSI_CustomerOrders: PK = customerId, SK = createdAt
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class Order {

    private String pk;
    private String sk;
    private String orderId;
    private String customerId;
    private List<OrderItem> items;
    private BigDecimal totalAmount;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
    private Long version;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("PK")
    public String getPk() { return pk; }

    @DynamoDbSortKey
    @DynamoDbAttribute("SK")
    public String getSk() { return sk; }

    @DynamoDbSecondaryPartitionKey(indexNames = "GSI_CustomerOrders")
    @DynamoDbAttribute("customerId")
    public String getCustomerId() { return customerId; }

    @DynamoDbSecondarySortKey(indexNames = "GSI_CustomerOrders")
    @DynamoDbAttribute("createdAt")
    public Instant getCreatedAt() { return createdAt; }

    @DynamoDbBean
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class OrderItem {
        private String skuId;
        private String productName;
        private int quantity;
        private BigDecimal unitPrice;
    }
}
