package com.orderflow.inventory.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

/**
 * DynamoDB item representing a product's inventory levels.
 *
 * Table key design:
 *   PK = "ITEM#<skuId>"   (no sort key — single record per SKU)
 *
 * Reservations are performed via conditional TransactWriteItems
 * (SET availableQty = availableQty - :qty WHERE availableQty >= :qty)
 * to prevent overselling without locking.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class InventoryItem {

    private String  pk;            // "ITEM#<skuId>"
    private String  skuId;
    private String  productName;
    private Integer availableQty;
    private Integer reservedQty;
    private Long    version;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("PK")
    public String getPk() { return pk; }

    public void setPk(String pk) { this.pk = pk; }
}
