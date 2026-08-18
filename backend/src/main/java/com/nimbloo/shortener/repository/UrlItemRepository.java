package com.nimbloo.shortener.repository;

import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import com.nimbloo.shortener.entity.UrlItem;

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.PageIterable;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeAction;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.AttributeValueUpdate;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

@Repository
public class UrlItemRepository {

    private static final String COUNTER_KEY = "__counter__";

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

    public boolean saveIfAbsent(UrlItem item) {
        Expression notExists = Expression.builder()
                .expression("attribute_not_exists(code)")
                .build();

        PutItemEnhancedRequest<UrlItem> request = PutItemEnhancedRequest.builder(UrlItem.class)
                .item(item)
                .conditionExpression(notExists)
                .build();
        try {
            table.putItem(request);
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    public Optional<UrlItem> findByCode(String code) {
        UrlItem item = table.getItem(Key.builder().partitionValue(code).build());
        return Optional.ofNullable(item);
    }

    public boolean existsByCode(String code) {
        return findByCode(code).isPresent();
    }

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

    public boolean disableActive(String code) {
        Map<String, AttributeValue> key = Map.of(
            "code", AttributeValue.builder().s(code).build()
        );

        UpdateItemRequest request = UpdateItemRequest.builder()
            .tableName(tableName)
            .key(key)
            .updateExpression("SET active = :false")
            .conditionExpression("attribute_exists(code)")
            .expressionAttributeValues(Map.of(
                ":false", AttributeValue.builder().bool(false).build()
            ))
            .build();

        try {
            lowLevelClient.updateItem(request);
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    public long incrementIdCounter() {
        Map<String, AttributeValue> key = Map.of(
            "code", AttributeValue.builder().s(COUNTER_KEY).build()
        );

        UpdateItemRequest request = UpdateItemRequest.builder()
            .tableName(tableName)
            .key(key)
            .updateExpression("ADD id_counter :inc")
            .expressionAttributeValues(Map.of(
                ":inc", AttributeValue.builder().n("1").build()
            ))
            .returnValues(ReturnValue.UPDATED_NEW)
            .build();

        UpdateItemResponse response = lowLevelClient.updateItem(request);
        return Long.parseLong(response.attributes().get("id_counter").n());
    }

    public PageIterable<UrlItem> findAllPaged(int pageSize, String lastEvaluatedKey) {
        Expression notCounter = Expression.builder()
                .expression("code <> :counter")
                .expressionValues(Map.of(
                    ":counter", AttributeValue.builder().s(COUNTER_KEY).build()
                ))
                .build();

        ScanEnhancedRequest.Builder builder = ScanEnhancedRequest.builder()
                .limit(pageSize)
                .filterExpression(notCounter);

        if (lastEvaluatedKey != null && !lastEvaluatedKey.isBlank()) {
            builder.exclusiveStartKey(Map.of("code", AttributeValue.builder().s(lastEvaluatedKey).build()));
        }

        return table.scan(builder.build());
    }
}
