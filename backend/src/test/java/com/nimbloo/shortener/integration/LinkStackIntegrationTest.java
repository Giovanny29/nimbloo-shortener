package com.nimbloo.shortener.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nimbloo.shortener.dto.CreateLinkRequest;
import com.nimbloo.shortener.dto.LinkResponse;
import com.nimbloo.shortener.entity.UrlItem;
import com.nimbloo.shortener.exception.ResourceNotFoundException;
import com.nimbloo.shortener.repository.UrlItemRepository;
import com.nimbloo.shortener.service.LinkService;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.localstack.LocalStackContainer.Service;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LinkStackIntegrationTest {

    @Container
    static final GenericContainer<?> dynamoDb = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:latest"))
            .withExposedPorts(8000)
            .withCommand("-jar DynamoDBLocal.jar -sharedDb -dbPath .");

    @Container
    static final LocalStackContainer localstack = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3.8.1"))
            .withServices(Service.SQS);

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void awsProperties(DynamicPropertyRegistry registry) {
        registry.add("aws.dynamodb.endpoint",
                () -> "http://" + dynamoDb.getHost() + ":" + dynamoDb.getMappedPort(8000));
        registry.add("aws.sqs.endpoint", () -> localstack.getEndpoint().toString());
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private LinkService linkService;

    @Autowired
    private UrlItemRepository repository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    @Order(1)
    void fullFlow_createRedirectClickDisable_withRealServices() {
        String code = "integracao";
        LinkResponse created = linkService.createLink(
                new CreateLinkRequest("https://example.com/origem", null, code));

        assertThat(created.code()).isEqualTo(code);

        assertThat(redisTemplate.opsForValue().get("link:" + code)).isNotNull();

        String originalUrl = linkService.getOriginalUrlForRedirect(code);
        assertThat(originalUrl).isEqualTo("https://example.com/origem");

        awaitClickCount(code, 1L);

        linkService.disableLink(code);

        assertThatThrownBy(() -> linkService.getOriginalUrlForRedirect(code))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("expirou ou foi desativado");

        UrlItem disabled = repository.findByCode(code).orElseThrow();
        assertThat(disabled.getActive()).isFalse();
        assertThat(disabled.getClickCount()).isEqualTo(1L);
    }

    @Test
    @Order(2)
    void disable_doesNotOverwriteClickCount_withRealDynamoDb() {
        String code = "concorrencia";
        linkService.createLink(new CreateLinkRequest("https://example.com/race", null, code));

        repository.incrementClickCount(code);
        repository.incrementClickCount(code);

        boolean disabled = repository.disableActive(code);

        assertThat(disabled).isTrue();
        UrlItem after = repository.findByCode(code).orElseThrow();
        assertThat(after.getClickCount()).isEqualTo(2L);
        assertThat(after.getActive()).isFalse();
    }

    @Test
    @Order(3)
    void redirect_fallsBackToDynamoDB_whenRedisStops() {
        String code = "outage";
        linkService.createLink(new CreateLinkRequest("https://example.com/outage", null, code));

        assertThat(redisTemplate.opsForValue().get("link:" + code)).isNotNull();

        redis.stop();

        assertThatThrownBy(() -> redisTemplate.opsForValue().get("link:" + code))
                .isInstanceOf(DataAccessException.class);

        String originalUrl = linkService.getOriginalUrlForRedirect(code);
        assertThat(originalUrl).isEqualTo("https://example.com/outage");
    }

    private void awaitClickCount(String code, long expected) {
        long deadline = System.currentTimeMillis() + 40_000;
        while (System.currentTimeMillis() < deadline) {
            UrlItem item = repository.findByCode(code).orElse(null);
            if (item != null && item.getClickCount() >= expected) {
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        fail("O contador de cliques para '" + code + "' não atingiu " + expected + " em 40s.");
    }
}