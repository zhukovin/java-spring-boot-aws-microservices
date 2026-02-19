package com.orderflow.inventory.repository;

import com.orderflow.inventory.model.InventoryIdempotencyRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

@Repository
@RequiredArgsConstructor
public class InventoryIdempotencyRepository {

    private final DynamoDbEnhancedClient enhancedClient;

    @Value("${aws.dynamodb.tables.inventory-idempotency}")
    private String tableName;

    private DynamoDbTable<InventoryIdempotencyRecord> table() {
        return enhancedClient.table(tableName,
                TableSchema.fromBean(InventoryIdempotencyRecord.class));
    }

    public boolean exists(String orderId) {
        Key key = Key.builder().partitionValue("PROCESSED#" + orderId).build();
        return table().getItem(key) != null;
    }

    public void save(InventoryIdempotencyRecord record) {
        table().putItem(record);
    }
}
