package com.orderflow.order.repository;

import com.orderflow.order.model.OutboxRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.*;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.UpdateItemEnhancedRequest;

import java.time.Instant;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class OutboxRepository {

    private final DynamoDbEnhancedClient enhancedClient;

    @Value("${aws.dynamodb.tables.outbox:order_outbox}")
    private String tableName;

    public DynamoDbTable<OutboxRecord> table() {
        return enhancedClient.table(tableName, TableSchema.fromBean(OutboxRecord.class));
    }

    public void save(OutboxRecord record) {
        table().putItem(record);
    }

    /**
     * Queries GSI_PendingOutbox (status=PENDING, sorted by createdAt ascending).
     * Returns up to {@code limit} records for the outbox poller to publish.
     */
    public List<OutboxRecord> findPending(int limit) {
        DynamoDbIndex<OutboxRecord> gsi = table().index("GSI_PendingOutbox");
        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.keyEqualTo(
                        Key.builder().partitionValue("PENDING").build()))
                .limit(limit)
                .build();
        return gsi.query(request)
                .stream()
                .flatMap(page -> page.items().stream())
                .toList();
    }

    /**
     * Transitions status PENDING → PUBLISHED after confirmed Kafka delivery.
     * Uses ignoreNulls=true so only the updated fields are written.
     */
    public void markPublished(String outboxId) {
        OutboxRecord patch = new OutboxRecord();
        patch.setPk("OUTBOX#" + outboxId);
        patch.setOutboxId(outboxId);
        patch.setStatus("PUBLISHED");
        patch.setPublishedAt(Instant.now());

        table().updateItem(UpdateItemEnhancedRequest.builder(OutboxRecord.class)
                .item(patch)
                .ignoreNulls(true)
                .build());
    }
}
