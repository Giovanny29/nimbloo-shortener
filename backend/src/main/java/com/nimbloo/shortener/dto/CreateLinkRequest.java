package com.nimbloo.shortener.dto;

import java.time.Instant;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateLinkRequest(

    @NotBlank(message = "A URL é obrigatória")
    @Pattern(
        regexp = "^https?://.+", 
        message = "A URL deve ser válida e começar com http:// ou https://"
    )
    String url,

    @Future(message = "A data de expiração deve ser uma data no futuro")
    Instant expiresAt,

    @Pattern(
        regexp = "^[a-zA-Z0-9_-]{3,30}$", 
        message = "O alias customizado deve conter apenas letras, números, hífen ou underline"
    )
    String alias
) {}