package com.nimbloo.shortener.exception;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
            "timestamp", Instant.now().toString(),
            "status", 404,
            "error", "Not Found",
            "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(AliasConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(AliasConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
            "timestamp", Instant.now().toString(),
            "status", 409,
            "error", "Conflict",
            "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(InvalidLinkException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(InvalidLinkException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
            "timestamp", Instant.now().toString(),
            "status", 400,
            "error", "Bad Request",
            "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String errorMessage = ex.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(err -> err.getField() + ": " + err.getDefaultMessage())
            .orElse("Requisição inválida");

        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
            .collect(java.util.stream.Collectors.toMap(
                org.springframework.validation.FieldError::getField,
                org.springframework.validation.FieldError::getDefaultMessage,
                (first, second) -> first));

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
            "timestamp", Instant.now().toString(),
            "status", 400,
            "error", "Bad Request",
            "message", errorMessage,
            "fieldErrors", fieldErrors
        ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableJson(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
            "timestamp", Instant.now().toString(),
            "status", 400,
            "error", "Bad Request",
            "message", "Corpo da requisição inválido ou malformado"
        ));
    }
}