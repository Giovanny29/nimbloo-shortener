#!/bin/bash
echo "=== Inicializando recursos Locais da AWS ==="

# Define endpoint local do LocalStack / DynamoDB Local
AWS_REGION="us-east-1"
LOCALSTACK_URL="http://localhost:4566"
DYNAMODB_URL="http://localhost:8000"

# 1. Cria Fila SQS no LocalStack 
echo "Criando Fila SQS no LocalStack..."
aws --endpoint-url=$LOCALSTACK_URL sqs create-queue \
    --queue-name nimbloo-click-events \
    --region $AWS_REGION

# 2. Cria Tabela no DynamoDB Local 
echo "Criando Tabela 'urls' no DynamoDB Local..."
aws --endpoint-url=$DYNAMODB_URL dynamodb create-table \
    --table-name urls \
    --attribute-definitions AttributeName=short_code,AttributeType=S \
    --key-schema AttributeName=short_code,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST \
    --region $AWS_REGION

echo "=== Recursos criados com sucesso! ==="