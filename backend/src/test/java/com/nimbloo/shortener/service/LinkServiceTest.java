package com.nimbloo.shortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

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
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.PageIterable;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

@ExtendWith(MockitoExtension.class)
class LinkServiceTest {

    private static final String BASE_URL = "http://localhost:8080";

    @Mock
    private UrlItemRepository repository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private SqsTemplate sqsTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private final Base62Encoder base62Encoder = new Base62Encoder();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private LinkService service;

    @BeforeEach
    void setUp() {
        service = new LinkService(repository, base62Encoder, redisTemplate, sqsTemplate, objectMapper);
        ReflectionTestUtils.setField(service, "baseUrl", BASE_URL);
        ReflectionTestUtils.setField(service, "queueName", "url-click-events");
    }

    // --- FLUXO FELIZ ---

    @Test
    void createLink_withAlias_shouldPersistAndReturnShortUrl() {
        when(repository.existsByCode("meu-link")).thenReturn(false);
        when(repository.saveIfAbsent(any(UrlItem.class))).thenReturn(true);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        LinkResponse response = service.createLink(new CreateLinkRequest("https://example.com/veiculo", null, "meu-link"));

        assertThat(response.code()).isEqualTo("meu-link");
        assertThat(response.shortUrl()).isEqualTo(BASE_URL + "/meu-link");
        assertThat(response.status()).isEqualTo(LinkStatus.ACTIVE);
        assertThat(response.clickCount()).isZero();

        verify(repository).existsByCode("meu-link");
        verify(repository).saveIfAbsent(any(UrlItem.class));
        verify(valueOps).set(startsWith("link:"), anyString(), any(Duration.class));
    }

    @Test
    void createLink_withoutAlias_shouldGenerateBase62CodeInDynamoDBCounter() {
        when(repository.incrementIdCounter()).thenReturn(1L);
        when(repository.saveIfAbsent(any(UrlItem.class))).thenReturn(true);

        LinkResponse response = service.createLink(new CreateLinkRequest("https://example.com", null, null));

        assertThat(response.code()).isEqualTo(base62Encoder.encode(1L));
        assertThat(response.shortUrl()).isEqualTo(BASE_URL + "/" + base62Encoder.encode(1L));
        verify(repository, never()).existsByCode(anyString());
        verify(repository, never()).save(any(UrlItem.class));
        verify(repository).saveIfAbsent(any(UrlItem.class));
    }

    @Test
    void createLink_withLongUrl_shouldTrimOriginalUrl() {
        when(repository.incrementIdCounter()).thenReturn(2L);
        when(repository.saveIfAbsent(any(UrlItem.class))).thenReturn(true);

        LinkResponse response = service.createLink(new CreateLinkRequest("  https://example.com/long  ", null, null));

        assertThat(response.originalUrl()).isEqualTo("https://example.com/long");
    }

    @Test
    void createLink_whenGeneratedCodeCollides_shouldRetryWithNextId() {
        when(repository.incrementIdCounter()).thenReturn(1L, 2L);
        when(repository.saveIfAbsent(any(UrlItem.class))).thenReturn(false, true);

        LinkResponse response = service.createLink(new CreateLinkRequest("https://example.com", null, null));

        assertThat(response.code()).isEqualTo(base62Encoder.encode(2L));
        verify(repository, times(2)).incrementIdCounter();
        verify(repository, times(2)).saveIfAbsent(any(UrlItem.class));
    }

    @Test
    void createLink_whenCodeGenerationExhausted_shouldThrowIllegalStateException() {
        when(repository.incrementIdCounter()).thenReturn(1L);
        when(repository.saveIfAbsent(any(UrlItem.class))).thenReturn(false);

        assertThatThrownBy(() -> service.createLink(new CreateLinkRequest("https://example.com", null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("código único");
    }

    @Test
    void createLink_whenAliasLostTheRace_shouldThrowAliasConflictException() {
        when(repository.existsByCode("race")).thenReturn(false);
        when(repository.saveIfAbsent(any(UrlItem.class))).thenReturn(false);

        assertThatThrownBy(() -> service.createLink(new CreateLinkRequest("https://example.com", null, "race")))
                .isInstanceOf(AliasConflictException.class)
                .hasMessageContaining("já está em uso");
    }

    @Test
    void getOriginalUrlForRedirect_shouldReturnOriginalUrlAndDispatchClickEvent() {
        UrlItem item = activeItem("abc1234", "https://example.com/target");
        when(repository.findByCode("abc1234")).thenReturn(Optional.of(item));

        String originalUrl = service.getOriginalUrlForRedirect("abc1234");

        assertThat(originalUrl).isEqualTo("https://example.com/target");
        verify(sqsTemplate, timeout(2000)).send(eq("url-click-events"), eq("abc1234"));
    }

    @Test
    void getOriginalUrlForRedirect_fromCache_shouldNotHitDatabase() throws Exception {
        UrlItem item = activeItem("abc1234", "https://example.com/target");
        String cachedJson = objectMapper.writeValueAsString(item);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("link:abc1234")).thenReturn(cachedJson);

        String originalUrl = service.getOriginalUrlForRedirect("abc1234");

        assertThat(originalUrl).isEqualTo("https://example.com/target");
        verify(repository, never()).findByCode(anyString());
    }

    @Test
    void getOriginalUrlForRedirect_withCachedDisabledEntry_shouldThrowNotFoundWithoutTouchingDatabase() throws Exception {
        UrlItem item = activeItem("abc1234", "https://example.com/target");
        item.setActive(false);
        String cachedJson = objectMapper.writeValueAsString(item);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("link:abc1234")).thenReturn(cachedJson);

        assertThatThrownBy(() -> service.getOriginalUrlForRedirect("abc1234"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("desativado");

        verify(repository, never()).findByCode(anyString());
        verify(sqsTemplate, never()).send(anyString(), anyString());
    }

    @Test
    void getOriginalUrlForRedirect_whenRedisReadFails_shouldFallbackToDatabase() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("link:abc1234"))
                .thenThrow(new RedisConnectionFailureException("Redis fora do ar"));
        UrlItem item = activeItem("abc1234", "https://example.com/target");
        when(repository.findByCode("abc1234")).thenReturn(Optional.of(item));

        String originalUrl = service.getOriginalUrlForRedirect("abc1234");

        assertThat(originalUrl).isEqualTo("https://example.com/target");
        verify(repository).findByCode("abc1234");
    }

    @Test
    void getOriginalUrlForRedirect_withCorruptedCache_shouldFallbackToDatabase() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("link:abc1234")).thenReturn("{invalid json");
        UrlItem item = activeItem("abc1234", "https://example.com/target");
        when(repository.findByCode("abc1234")).thenReturn(Optional.of(item));

        String originalUrl = service.getOriginalUrlForRedirect("abc1234");

        assertThat(originalUrl).isEqualTo("https://example.com/target");
    }

    @Test
    void getLinkDetails_shouldReturnDetailsWithClickCount() {
        UrlItem item = activeItem("abc1234", "https://example.com/target");
        item.setClickCount(7L);
        when(repository.findByCode("abc1234")).thenReturn(Optional.of(item));

        LinkResponse response = service.getLinkDetails("abc1234");

        assertThat(response.code()).isEqualTo("abc1234");
        assertThat(response.clickCount()).isEqualTo(7L);
    }

    @Test
    void disableLink_shouldDisableAtomicallyAndSyncDisabledStateToCache() {
        UrlItem disabledItem = activeItem("abc1234", "https://example.com/target");
        disabledItem.setActive(false);
        when(repository.disableActive("abc1234")).thenReturn(true);
        when(repository.findByCode("abc1234")).thenReturn(Optional.of(disabledItem));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service.disableLink("abc1234");

        verify(repository).disableActive("abc1234");
        verify(repository).findByCode("abc1234");
        verify(valueOps).set(startsWith("link:"), anyString(), any(Duration.class));
    }

    @Test
    void disableLink_whenCacheSyncFails_shouldStillSucceed() {
        when(repository.disableActive("abc1234")).thenReturn(true);
        when(repository.findByCode("abc1234"))
                .thenThrow(new RedisConnectionFailureException("Redis fora do ar"));

        service.disableLink("abc1234");

        verify(repository).disableActive("abc1234");
    }

    // --- CAMINHOS DE ERRO ---

    @Test
    void createLink_withInvalidScheme_shouldThrowInvalidLinkException() {
        CreateLinkRequest request = new CreateLinkRequest("ftp://example.com", null, null);

        assertThatThrownBy(() -> service.createLink(request))
                .isInstanceOf(InvalidLinkException.class)
                .hasMessageContaining("HTTP ou HTTPS");
    }

    @Test
    void createLink_withMalformedUrl_shouldThrowInvalidLinkException() {
        CreateLinkRequest request = new CreateLinkRequest("not a url", null, null);

        assertThatThrownBy(() -> service.createLink(request))
                .isInstanceOf(InvalidLinkException.class);
    }

    @Test
    void createLink_withExpirationInThePast_shouldThrowInvalidLinkException() {
        CreateLinkRequest request = new CreateLinkRequest(
                "https://example.com",
                Instant.now().minus(1, ChronoUnit.HOURS),
                null);

        assertThatThrownBy(() -> service.createLink(request))
                .isInstanceOf(InvalidLinkException.class)
                .hasMessageContaining("passado");
    }

    @Test
    void createLink_withTakenAlias_shouldThrowAliasConflictException() {
        when(repository.existsByCode("taken")).thenReturn(true);

        CreateLinkRequest request = new CreateLinkRequest("https://example.com", null, "taken");

        assertThatThrownBy(() -> service.createLink(request))
                .isInstanceOf(AliasConflictException.class)
                .hasMessageContaining("já está em uso");
    }

    @Test
    void createLink_withInvalidAliasFormat_shouldThrowInvalidLinkException() {
        CreateLinkRequest request = new CreateLinkRequest("https://example.com", null, "a b!");

        assertThatThrownBy(() -> service.createLink(request))
                .isInstanceOf(InvalidLinkException.class)
                .hasMessageContaining("O alias deve conter");
    }

    @Test
    void createLink_withReservedAlias_shouldThrowAliasConflictExceptionWithoutLeakingReservation() {
        CreateLinkRequest request = new CreateLinkRequest("https://example.com", null, "__counter__");

        assertThatThrownBy(() -> service.createLink(request))
                .isInstanceOf(AliasConflictException.class)
                .hasMessageContaining("já está em uso");

        verify(repository, never()).saveIfAbsent(any(UrlItem.class));
    }

    @Test
    void getOriginalUrlForRedirect_withReservedCode_shouldThrowNotFoundWithoutTouchingInfra() {
        assertThatThrownBy(() -> service.getOriginalUrlForRedirect("__counter__"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("não encontrado");

        verify(repository, never()).findByCode(anyString());
        verify(sqsTemplate, never()).send(anyString(), anyString());
    }

    @Test
    void getLinkDetails_withReservedCode_shouldThrowNotFound() {
        assertThatThrownBy(() -> service.getLinkDetails("__counter__"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("não encontrado");

        verify(repository, never()).findByCode(anyString());
    }

    @Test
    void disableLink_withReservedCode_shouldThrowNotFoundAndNeverSave() {
        assertThatThrownBy(() -> service.disableLink("__counter__"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("não encontrado");

        verify(repository, never()).findByCode(anyString());
        verify(repository, never()).save(any(UrlItem.class));
    }

    @Test
    void getOriginalUrlForRedirect_whenExpired_shouldThrowResourceNotFoundException() {
        UrlItem item = activeItem("abc1234", "https://example.com/target");
        item.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS).toString());
        when(repository.findByCode("abc1234")).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service.getOriginalUrlForRedirect("abc1234"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("expirou");

        verify(sqsTemplate, never()).send(anyString(), anyString());
    }

    @Test
    void getOriginalUrlForRedirect_whenDisabled_shouldThrowResourceNotFoundException() {
        UrlItem item = activeItem("abc1234", "https://example.com/target");
        item.setActive(false);
        when(repository.findByCode("abc1234")).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service.getOriginalUrlForRedirect("abc1234"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("desativado");
    }

    @Test
    void getOriginalUrlForRedirect_whenNotFound_shouldThrowResourceNotFoundException() {
        when(repository.findByCode("nonexistent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOriginalUrlForRedirect("nonexistent"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getLinkDetails_whenNotFound_shouldThrowResourceNotFoundException() {
        when(repository.findByCode("nonexistent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getLinkDetails("nonexistent"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void disableLink_whenNotFound_shouldThrowResourceNotFoundException() {
        when(repository.disableActive("nonexistent")).thenReturn(false);

        assertThatThrownBy(() -> service.disableLink("nonexistent"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getAllLinksPaged_pageSizeAboveMax_isClampedToOneHundred() {
        PageIterable<UrlItem> emptyPages = mock(PageIterable.class);
        when(emptyPages.stream()).thenReturn(Stream.empty());
        when(repository.findAllPaged(eq(100), isNull())).thenReturn(emptyPages);

        PagedLinkResponse response = service.getAllLinksPaged(5000, null);

        assertThat(response.pageSize()).isEqualTo(100);
        verify(repository).findAllPaged(100, null);
    }

    @Test
    void getAllLinksPaged_pageSizeBelowOne_isClampedToOne() {
        PageIterable<UrlItem> emptyPages = mock(PageIterable.class);
        when(emptyPages.stream()).thenReturn(Stream.empty());
        when(repository.findAllPaged(eq(1), isNull())).thenReturn(emptyPages);

        PagedLinkResponse response = service.getAllLinksPaged(-5, null);

        assertThat(response.pageSize()).isEqualTo(1);
        verify(repository).findAllPaged(1, null);
    }

    @Test
    void getAllLinksPaged_whenFirstPageContainsOnlyFilteredItems_shouldAdvanceCursorUntilItFillsThePage() {
        Page<UrlItem> counterOnlyPage = mock(Page.class);
        when(counterOnlyPage.items()).thenReturn(List.of());
        when(counterOnlyPage.lastEvaluatedKey()).thenReturn(
                Map.of("code", AttributeValue.builder().s("__counter__").build()));

        Page<UrlItem> realPage = mock(Page.class);
        when(realPage.items()).thenReturn(List.of(
                activeItem("abc1234", "https://example.com/1"),
                activeItem("abc1235", "https://example.com/2")));
        when(realPage.lastEvaluatedKey()).thenReturn(null);

        PageIterable<UrlItem> counterIterable = mock(PageIterable.class);
        when(counterIterable.stream()).thenReturn(Stream.of(counterOnlyPage));
        PageIterable<UrlItem> realIterable = mock(PageIterable.class);
        when(realIterable.stream()).thenReturn(Stream.of(realPage));

        when(repository.findAllPaged(2, null)).thenReturn(counterIterable);
        when(repository.findAllPaged(2, "__counter__")).thenReturn(realIterable);

        PagedLinkResponse response = service.getAllLinksPaged(2, null);

        assertThat(response.items()).extracting(LinkResponse::code)
                .containsExactly("abc1234", "abc1235");
        assertThat(response.hasMore()).isFalse();
        verify(repository).findAllPaged(2, null);
        verify(repository).findAllPaged(2, "__counter__");
    }

    private UrlItem activeItem(String code, String originalUrl) {
        return new UrlItem(code, originalUrl, null);
    }
}