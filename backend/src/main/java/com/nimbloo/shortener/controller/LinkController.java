package com.nimbloo.shortener.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nimbloo.shortener.dto.CreateLinkRequest;
import com.nimbloo.shortener.dto.LinkResponse;
import com.nimbloo.shortener.dto.PagedLinkResponse;
import com.nimbloo.shortener.service.LinkService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/links")
public class LinkController {

    private final LinkService linkService;

    public LinkController(LinkService linkService) {
        this.linkService = linkService;
    }

    /**
     * POST /api/v1/links
     * Cria link curto. Body: { "url": "...", "expiresAt": "...", "alias": "..." }
     */
    @PostMapping
    public ResponseEntity<LinkResponse> createLink(@Valid @RequestBody CreateLinkRequest request) {
        LinkResponse response = linkService.createLink(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /api/v1/links
     * Lista links (paginada por cursor do DynamoDB)
     */
    @GetMapping
    public ResponseEntity<PagedLinkResponse> getAllLinks(
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String lastKey) {
        
        PagedLinkResponse response = linkService.getAllLinksPaged(pageSize, lastKey);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/links/{code}
     * Detalhe do link + total de cliques
     */
    @GetMapping("/{code}")
    public ResponseEntity<LinkResponse> getLinkDetails(@PathVariable String code) {
        LinkResponse response = linkService.getLinkDetails(code);
        return ResponseEntity.ok(response);
    }

    /**
     * DELETE /api/v1/links/{code}
     * Desativa o link
     */
    @DeleteMapping("/{code}")
    public ResponseEntity<Void> disableLink(@PathVariable String code) {
        linkService.disableLink(code);
        return ResponseEntity.noContent().build(); // 204 No Content
    }
}