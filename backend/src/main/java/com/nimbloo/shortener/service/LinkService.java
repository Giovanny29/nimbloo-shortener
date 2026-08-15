package com.nimbloo.shortener.service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbloo.shortener.dto.CreateLinkRequest;
import com.nimbloo.shortener.dto.LinkResponse;
import com.nimbloo.shortener.dto.PagedLinkResponse;
import com.nimbloo.shortener.entity.LinkStatus;
import com.nimbloo.shortener.entity.UrlItem;
import com.nimbloo.shortener.exception.AliasConflictException;
import com.nimbloo.shortener.exception.InvalidLinkException;
import com.nimbloo.shortener.exception.ResourceNotFoundException;
import com.nimbloo.shortener.repository.UrlItemRepository;
import com.nimbloo.shortener.util.Base62Encoder;

import io.awspring.cloud.sqs.operations.SqsTemplate;
import software.amazon.awssdk.enhanced.dynamodb.model.PageIterable;

@Service
public class LinkService {

    private static final Logger log = LoggerFactory.getLogger(LinkService.class);
    private static final String REDIS_KEY_PREFIX = "link:";
    private static final Duration DEFAULT_CACHE_TTL = Duration.ofHours(24);
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_CODE_GENERATION_ATTEMPTS = 10;

    private final UrlItemRepository repository;
    private final Base62Encoder base62Encoder;
    private final StringRedisTemplate redisTemplate;
    private final SqsTemplate sqsTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    @Value("${aws.sqs.queue-name:url-click-queue}")
    private String queueName;

    public LinkService(UrlItemRepository repository,
                       Base62Encoder base62Encoder,
                       StringRedisTemplate redisTemplate,
                       SqsTemplate sqsTemplate,
                       ObjectMapper objectMapper) {
        this.repository = repository;
        this.base62Encoder = base62Encoder;
        this.redisTemplate = redisTemplate;
        this.sqsTemplate = sqsTemplate;
        this.objectMapper = objectMapper;
    }

    public LinkResponse createLink(CreateLinkRequest request) {
        validateUrl(request.url());
        validateExpirationDate(request.expiresAt());

        String expiresAtStr = request.expiresAt() != null ? request.expiresAt().toString() : null;

        UrlItem item;
        if (request.alias() != null && !request.alias().isBlank()) {
            String code = request.alias().trim();

            if (!code.matches("^[a-zA-Z0-9_-]{3,30}$")) {
                throw new InvalidLinkException("O alias deve conter apenas letras, números, hífen ou underline (3 a 30 caracteres).");
            }

            if (repository.existsByCode(code)) {
                throw new AliasConflictException("O alias '" + code + "' já está em uso por outro link.");
            }

            item = new UrlItem(code, request.url().trim(), expiresAtStr);
            if (!repository.saveIfAbsent(item)) {
                throw new AliasConflictException("O alias '" + code + "' já está em uso por outro link.");
            }
        } else {
            item = generateUniqueItem(request.url().trim(), expiresAtStr);
        }

        cacheLinkItem(item);

        return LinkResponse.fromEntity(item, baseUrl);
    }

    private UrlItem generateUniqueItem(String url, String expiresAtStr) {
        for (int attempt = 0; attempt < MAX_CODE_GENERATION_ATTEMPTS; attempt++) {
            long nextId = repository.incrementIdCounter();
            UrlItem candidate = new UrlItem(base62Encoder.encode(nextId), url, expiresAtStr);

            if (repository.saveIfAbsent(candidate)) {
                return candidate;
            }
        }

        throw new IllegalStateException("Não foi possível gerar um código único após " + MAX_CODE_GENERATION_ATTEMPTS + " tentativas.");
    }

    public String getOriginalUrlForRedirect(String code) {
        UrlItem item = findUrlItemByCodeWithCache(code);

        LinkStatus status = LinkStatus.calculateStatus(item.getActive(), item.getExpiresAt());

        if (status == LinkStatus.DISABLED || status == LinkStatus.EXPIRED) {
            throw new ResourceNotFoundException("O link solicitado expirou ou foi desativado.");
        }

        dispatchClickEventAsync(code);

        return item.getOriginalUrl();
    }

    public LinkResponse getLinkDetails(String code) {
        UrlItem item = repository.findByCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("Link não encontrado para o código: " + code));

        return LinkResponse.fromEntity(item, baseUrl);
    }

    public PagedLinkResponse getAllLinksPaged(int pageSize, String lastEvaluatedKey) {
        int safePageSize = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));

        PageIterable<UrlItem> pagedResult = repository.findAllPaged(safePageSize, lastEvaluatedKey);

        var page = pagedResult.stream().findFirst();

        if (page.isEmpty()) {
            return PagedLinkResponse.of(List.of(), null, safePageSize);
        }

        List<LinkResponse> items = page.get().items().stream()
                .map(item -> LinkResponse.fromEntity(item, baseUrl))
                .toList();

        String nextCursor = null;
        if (page.get().lastEvaluatedKey() != null && !page.get().lastEvaluatedKey().isEmpty()) {
            nextCursor = page.get().lastEvaluatedKey().get("code").s();
        }

        return PagedLinkResponse.of(items, nextCursor, safePageSize);
    }

    public void disableLink(String code) {
        UrlItem item = repository.findByCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("Link não encontrado para o código: " + code));

        item.setActive(false);
        repository.save(item);

        redisTemplate.delete(REDIS_KEY_PREFIX + code);
    }

    private UrlItem findUrlItemByCodeWithCache(String code) {
        String cachedJson = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + code);
        if (cachedJson != null) {
            try {
                return objectMapper.readValue(cachedJson, UrlItem.class);
            } catch (Exception e) {
                log.warn("Falha ao desserializar JSON do Redis para a chave {}: {}", code, e.getMessage());
            }
        }

        UrlItem item = repository.findByCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("Link não encontrado para o código: " + code));

        cacheLinkItem(item);
        return item;
    }

    private void cacheLinkItem(UrlItem item) {
        try {
            String json = objectMapper.writeValueAsString(item);
            redisTemplate.opsForValue().set(
                REDIS_KEY_PREFIX + item.getCode(),
                json,
                DEFAULT_CACHE_TTL
            );
        } catch (Exception e) {
            log.warn("Erro ao salvar link no Redis [code={}]: {}", item.getCode(), e.getMessage());
        }
    }

    private void dispatchClickEventAsync(String code) {
        try {
            sqsTemplate.send(queueName, code);
        } catch (Exception e) {
            log.error("Erro ao disparar evento de clique no SQS para o código {}: {}", code, e.getMessage());
        }
    }

    private void validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new InvalidLinkException("A URL original não pode ser vazia.");
        }

        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();

            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                throw new InvalidLinkException("A URL deve utilizar o protocolo HTTP ou HTTPS.");
            }

            if (uri.getHost() == null) {
                throw new InvalidLinkException("A URL informada é malformada ou inválida.");
            }
        } catch (Exception ex) {
            throw new InvalidLinkException("A URL informada possui um formato inválido: " + ex.getMessage());
        }
    }

    private void validateExpirationDate(Instant expiresAt) {
        if (expiresAt != null) {
            if (expiresAt.isBefore(Instant.now())) {
                throw new InvalidLinkException("A data de expiração não pode ser no passado.");
            }
        }
    }
}
