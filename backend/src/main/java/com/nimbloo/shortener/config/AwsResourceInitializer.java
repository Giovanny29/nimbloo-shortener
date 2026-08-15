package com.nimbloo.shortener.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class AwsResourceInitializer {

    private final DynamoDbClient dynamoDbClient;
    private final SqsClient sqsClient;

    @Value("${aws.dynamodb.table-name}")
    private String tableName;

    @Value("${aws.sqs.queue-name}")
    private String queueName;

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAwsResources() {
        createDynamoDbTableIfNotExists();
        createSqsQueueIfNotExists();
    }

    private void createDynamoDbTableIfNotExists() {
        try {
            dynamoDbClient.describeTable(DescribeTableRequest.builder().tableName(tableName).build());
            log.info("Tabela DynamoDB '{}' já existe.", tableName);
        } catch (ResourceNotFoundException e) {
            log.info("Tabela '{}' não encontrada. Criando...", tableName);
            CreateTableRequest request = CreateTableRequest.builder()
                    .tableName(tableName)
                    .attributeDefinitions(
                            AttributeDefinition.builder()
                                    .attributeName("code") // Alterado de "shortCode" para "code"
                                    .attributeType(ScalarAttributeType.S)
                                    .build()
                    )
                    .keySchema(
                            KeySchemaElement.builder()
                                    .attributeName("code") // Alterado de "shortCode" para "code"
                                    .keyType(KeyType.HASH)
                                    .build()
                    )
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .build();

            dynamoDbClient.createTable(request);
            log.info("Tabela DynamoDB '{}' criada com sucesso!", tableName);
        } catch (Exception e) {
            log.error("Erro ao verificar/criar tabela DynamoDB: {}", e.getMessage());
        }
    }

    private void createSqsQueueIfNotExists() {
        try {
            sqsClient.createQueue(CreateQueueRequest.builder().queueName(queueName).build());
            log.info("Fila SQS '{}' verificada/criada com sucesso!", queueName);
        } catch (Exception e) {
            log.error("Erro ao criar fila SQS: {}", e.getMessage());
        }
    }
}