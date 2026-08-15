package com.nimbloo.shortener.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.nimbloo.shortener.service.LinkService;

@RestController
public class RedirectController {

    private final LinkService linkService;

    public RedirectController(LinkService linkService) {
        this.linkService = linkService;
    }

    @GetMapping("/{code}")
    public ResponseEntity<Void> redirectToOriginalUrl(@PathVariable String code) {
        String originalUrl = linkService.getOriginalUrlForRedirect(code);

        return ResponseEntity
                .status(HttpStatus.FOUND) // 302 Redirect
                .header(HttpHeaders.LOCATION, originalUrl)
                .build();
    }
}