package com.orderflow.inventory.repository;

import com.orderflow.common.events.OrderCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class InventoryRepository {

    private final DynamoDbClient dynamoDbClient;

    @Value("${aws.dynamodb.tables.inventory}")
    private String tableName;

    /**
     * Atomically reserves stock for all order items in a single DynamoDB transaction.
     *
     * Each item's {@code Update} uses a condition expression
     * ({@code availableQty >= :qty}) so that the entire transaction is cancelled
     * if any SKU has insufficient stock — guaranteeing no partial reservations.
     *
     * @throws software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException
     *         when any item has insufficient available quantity
     */
    public void reserveItemsTransactionally(List<OrderCreatedEvent.OrderItem> items) {
        List<TransactWriteItem> writeItems = items.stream()
                .map(item -> TransactWriteItem.builder()
                        .update(Update.builder()
                                .tableName(tableName)
                                .key(Map.of("PK", AttributeValue.fromS("ITEM#" + item.skuId())))
                                .updateExpression(
                                        "SET availableQty = availableQty - :qty, " +
                                        "reservedQty = reservedQty + :qty, " +
                                        "#v = #v + :one")
                                .conditionExpression("availableQty >= :qty")
                                .expressionAttributeValues(Map.of(
                                        ":qty", AttributeValue.fromN(String.valueOf(item.quantity())),
                                        ":one", AttributeValue.fromN("1")))
                                .expressionAttributeNames(Map.of("#v", "version"))
                                .build())
                        .build())
                .collect(Collectors.toList());

        dynamoDbClient.transactWriteItems(
                TransactWriteItemsRequest.builder()
                        .transactItems(writeItems)
                        .build());
    }
}
