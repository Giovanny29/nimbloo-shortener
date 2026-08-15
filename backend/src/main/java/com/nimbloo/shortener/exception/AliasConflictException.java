package com.nimbloo.shortener.exception;

public class AliasConflictException extends RuntimeException {
    public AliasConflictException(String message) {
        super(message);
    }
}