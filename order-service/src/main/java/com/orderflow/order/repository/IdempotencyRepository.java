package com.orderflow.order.repository;

import com.orderflow.order.model.IdempotencyRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class IdempotencyRepository {

    private final DynamoDbEnhancedClient enhancedClient;

    @Value("${aws.dynamodb.tables.idempotency:order_idempotency_keys}")
    private String tableName;

    public DynamoDbTable<IdempotencyRecord> table() {
        return enhancedClient.table(tableName, TableSchema.fromBean(IdempotencyRecord.class));
    }

    public Optional<IdempotencyRecord> findByKey(String idempotencyKey) {
        Key key = Key.builder()
                .partitionValue("IDEMPOTENCY#" + idempotencyKey)
                .build();
        return Optional.ofNullable(table().getItem(key));
    }
}
