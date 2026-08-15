package com.nimbloo.shortener.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.nimbloo.shortener.repository.UrlItemRepository;

import io.awspring.cloud.sqs.annotation.SqsListener;

@Component
public class SqsClickConsumer {

    private static final Logger log = LoggerFactory.getLogger(SqsClickConsumer.class);
    private final UrlItemRepository repository;

    public SqsClickConsumer(UrlItemRepository repository) {
        this.repository = repository;
    }

    @SqsListener("${aws.sqs.queue-name:url-click-events}")
    public void listen(String code) {
        log.debug("Processando registro de clique para o link: {}", code);
        try {
            repository.incrementClickCount(code);
        } catch (Exception e) {
            log.error("Erro ao incrementar contador no DynamoDB para o código: {}. Reprocessando...", code, e);
            throw e; // Lança para o Spring acionar a política de retry do SQS
        }
    }
}