#!/bin/bash
echo "Inicializando recursos no LocalStack..."
awslocal sqs create-queue --queue-name link-clicks-queue
echo "Fila 'link-clicks-queue' criada com sucesso!"