package com.nimbloo.shortener.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nimbloo.shortener.dto.CreateLinkRequest;
import com.nimbloo.shortener.dto.LinkResponse;
import com.nimbloo.shortener.dto.PagedLinkResponse;
import com.nimbloo.shortener.entity.LinkStatus;
import com.nimbloo.shortener.entity.UrlItem;
import com.nimbloo.shortener.exception.AliasConflictException;
import com.nimbloo.shortener.exception.GlobalExceptionHandler;
import com.nimbloo.shortener.exception.InvalidLinkException;
import com.nimbloo.shortener.exception.ResourceNotFoundException;
import com.nimbloo.shortener.service.LinkService;

@ExtendWith(MockitoExtension.class)
class LinkControllerTest {

    private static final String BASE_URL = "http://localhost:8080";

    @Mock
    private LinkService linkService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders
                .standaloneSetup(new LinkController(linkService), new RedirectController(linkService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    // --- FLUXO FELIZ ---

    @Test
    void postLink_shouldReturn201WithShortUrl() throws Exception {
        UrlItem item = new UrlItem("meu-link", "https://example.com", null);
        when(linkService.createLink(any(CreateLinkRequest.class)))
                .thenReturn(LinkResponse.fromEntity(item, BASE_URL));

        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com","alias":"meu-link"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("meu-link"))
                .andExpect(jsonPath("$.shortUrl").value(BASE_URL + "/meu-link"))
                .andExpect(jsonPath("$.status").value(LinkStatus.ACTIVE.name()));
    }

    @Test
    void getRedirect_shouldReturn302WithLocationHeader() throws Exception {
        when(linkService.getOriginalUrlForRedirect("abc1234"))
                .thenReturn("https://example.com/target");

        mockMvc.perform(get("/abc1234"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/target"));
    }

    @Test
    void getRedirect_pathOutsideCodeCharset_shouldNotReachService() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isNotFound());

        verify(linkService, never()).getOriginalUrlForRedirect(anyString());
    }

    @Test
    void getLinks_shouldReturnPagedResponse() throws Exception {
        when(linkService.getAllLinksPaged(10, null))
                .thenReturn(PagedLinkResponse.of(List.of(), null, 10));

        mockMvc.perform(get("/api/v1/links"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void getLinkDetails_shouldReturnDetails() throws Exception {
        UrlItem item = new UrlItem("abc1234", "https://example.com", null);
        item.setClickCount(3L);
        when(linkService.getLinkDetails("abc1234"))
                .thenReturn(LinkResponse.fromEntity(item, BASE_URL));

        mockMvc.perform(get("/api/v1/links/abc1234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("abc1234"))
                .andExpect(jsonPath("$.clickCount").value(3));
    }

    @Test
    void deleteLink_shouldReturn204() throws Exception {
        mockMvc.perform(delete("/api/v1/links/abc1234"))
                .andExpect(status().isNoContent());

        verify(linkService).disableLink("abc1234");
    }

    // --- CAMINHOS DE ERRO ---

    @Test
    void postLink_withBlankUrl_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postLink_withInvalidUrlScheme_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"ftp://example.com\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postLink_withPastExpiration_shouldReturn400() throws Exception {
        String past = Instant.now().minus(1, ChronoUnit.HOURS).toString();

        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateLinkRequest("https://example.com", Instant.parse(past), null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postLink_withMalformedJson_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-valid-json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postLink_withTakenAlias_shouldReturn409() throws Exception {
        when(linkService.createLink(any(CreateLinkRequest.class)))
                .thenThrow(new AliasConflictException("O alias 'taken' já está em uso por outro link."));

        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\",\"alias\":\"taken\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("O alias 'taken' já está em uso por outro link."));
    }

    @Test
    void postLink_withInvalidUrl_shouldReturn400FromService() throws Exception {
        when(linkService.createLink(any(CreateLinkRequest.class)))
                .thenThrow(new InvalidLinkException("A URL informada é malformada ou inválida."));

        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getRedirect_unknownCode_shouldReturn404() throws Exception {
        when(linkService.getOriginalUrlForRedirect("nonexistent"))
                .thenThrow(new ResourceNotFoundException("Link não encontrado para o código: nonexistent"));

        mockMvc.perform(get("/nonexistent"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getLinkDetails_unknownCode_shouldReturn404() throws Exception {
        when(linkService.getLinkDetails("nonexistent"))
                .thenThrow(new ResourceNotFoundException("Link não encontrado para o código: nonexistent"));

        mockMvc.perform(get("/api/v1/links/nonexistent"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void deleteLink_unknownCode_shouldReturn404() throws Exception {
        doThrow(new ResourceNotFoundException("Link não encontrado para o código: nonexistent"))
                .when(linkService).disableLink("nonexistent");

        mockMvc.perform(delete("/api/v1/links/nonexistent"))
                .andExpect(status().isNotFound());
    }
}