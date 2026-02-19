package com.orderflow.order.repository;

import com.orderflow.order.model.Order;
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
public class OrderRepository {

    private final DynamoDbEnhancedClient enhancedClient;

    @Value("${aws.dynamodb.tables.orders:orders}")
    private String tableName;

    public DynamoDbTable<Order> table() {
        return enhancedClient.table(tableName, TableSchema.fromBean(Order.class));
    }

    public Optional<Order> findById(String orderId) {
        Key key = Key.builder()
                .partitionValue("ORDER#" + orderId)
                .sortValue("METADATA")
                .build();
        return Optional.ofNullable(table().getItem(key));
    }
}
