package com.nimbloo.shortener.dto;

import java.time.Instant;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateLinkRequest(

    @NotBlank(message = "A URL é obrigatória")
    @Size(max = 2048, message = "A URL deve ter no máximo 2048 caracteres")
    @Pattern(
        regexp = "^https?://[a-zA-Z0-9]([a-zA-Z0-9._-]*[a-zA-Z0-9])?([:/?#].*)?$",
        message = "A URL deve começar com http:// ou https:// e ser bem formada"
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