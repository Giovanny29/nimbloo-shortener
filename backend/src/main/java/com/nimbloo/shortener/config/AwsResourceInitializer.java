package com.nimbloo.shortener.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.SetQueueAttributesRequest;

import java.util.Map;

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
                                    .attributeName("code") 
                                    .attributeType(ScalarAttributeType.S)
                                    .build()
                    )
                    .keySchema(
                            KeySchemaElement.builder()
                                    .attributeName("code") 
                                    .keyType(KeyType.HASH)
                                    .build()
                    )
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .build();

            dynamoDbClient.createTable(request);
            log.info("Tabela DynamoDB '{}' criada com sucesso!", tableName);
        } catch (Exception e) {
            log.error("Erro ao verificar/criar tabela DynamoDB: {}", e.getMessage());
            throw new IllegalStateException("Falha ao verificar/criar a tabela DynamoDB '" + tableName
                    + "' — o DynamoDB é obrigatório para o serviço. Abortando boot.", e);
        }
    }

    private void createSqsQueueIfNotExists() {
        try {
            String dlqUrl = ensureQueue(dlqName());
            String queueUrl = ensureQueue(queueName);

            String dlqArn = sqsClient.getQueueAttributes(GetQueueAttributesRequest.builder()
                            .queueUrl(dlqUrl)
                            .attributeNames(QueueAttributeName.QUEUE_ARN)
                            .build())
                    .attributes().get(QueueAttributeName.QUEUE_ARN);

            String redrivePolicy = "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"5\"}";
            sqsClient.setQueueAttributes(SetQueueAttributesRequest.builder()
                    .queueUrl(queueUrl)
                    .attributes(Map.of(QueueAttributeName.REDRIVE_POLICY, redrivePolicy))
                    .build());

            log.info("Fila SQS '{}' verificada/criada com sucesso (DLQ: {})!", queueName, dlqName());
        } catch (Exception e) {
            log.error("Erro ao verificar/criar fila SQS: {} — SQS é opcional (métricas de clique); o boot continua.", e.getMessage());
        }
    }

    private String ensureQueue(String name) {
        try {
            return sqsClient.getQueueUrl(GetQueueUrlRequest.builder().queueName(name).build()).queueUrl();
        } catch (QueueDoesNotExistException e) {
            return sqsClient.createQueue(CreateQueueRequest.builder().queueName(name).build()).queueUrl();
        }
    }

    private String dlqName() {
        return queueName + "-dlq";
    }
}