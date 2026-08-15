package com.nimbloo.shortener.repository;

import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import com.nimbloo.shortener.entity.UrlItem;

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.PageIterable;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeAction;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.AttributeValueUpdate;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

@Repository
public class UrlItemRepository {

    private final DynamoDbTable<UrlItem> table;
    private final DynamoDbClient lowLevelClient;
    private final String tableName;

    public UrlItemRepository(DynamoDbEnhancedClient enhancedClient,
                             DynamoDbClient lowLevelClient,
                             @Value("${aws.dynamodb.table-name}") String tableName) {
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(UrlItem.class));
        this.lowLevelClient = lowLevelClient;
        this.tableName = tableName;
    }

    public void save(UrlItem item) {
        table.putItem(item);
    }

    public Optional<UrlItem> findByCode(String code) {
        UrlItem item = table.getItem(Key.builder().partitionValue(code).build());
        return Optional.ofNullable(item);
    }

    public boolean existsByCode(String code) {
        return findByCode(code).isPresent();
    }

    /**
     * INCREMENTO ATÔMICO NATIVO NO DYNAMODB
     * Executa um UPDATE com ação ADD diretamente no banco, evitando condições de corrida (Race Conditions)
     * e eliminando a necessidade de fazer um SELECT prévio.
     */
    public void incrementClickCount(String code) {
        Map<String, AttributeValue> key = Map.of(
            "code", AttributeValue.builder().s(code).build()
        );

        Map<String, AttributeValueUpdate> updates = Map.of(
            "click_count", AttributeValueUpdate.builder()
                .value(AttributeValue.builder().n("1").build())
                .action(AttributeAction.ADD)
                .build()
        );

        UpdateItemRequest request = UpdateItemRequest.builder()
            .tableName(tableName)
            .key(key)
            .attributeUpdates(updates)
            .build();

        lowLevelClient.updateItem(request);
    }

    /**
     * PAGINAÇÃO DE SCAN RESILIENTE
     */
    public PageIterable<UrlItem> findAllPaged(int pageSize, String lastEvaluatedKey) {
        ScanEnhancedRequest.Builder builder = ScanEnhancedRequest.builder().limit(pageSize);

        if (lastEvaluatedKey != null && !lastEvaluatedKey.isBlank()) {
            builder.exclusiveStartKey(Map.of("code", AttributeValue.builder().s(lastEvaluatedKey).build()));
        }

        return table.scan(builder.build());
    }
}