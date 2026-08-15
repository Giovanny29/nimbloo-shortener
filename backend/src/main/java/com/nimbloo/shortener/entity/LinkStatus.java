package com.nimbloo.shortener.entity;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public enum LinkStatus {
    ACTIVE,
    EXPIRED,
    DISABLED;

    private static final Logger log = LoggerFactory.getLogger(LinkStatus.class);

    public static LinkStatus calculateStatus(Boolean active, String expiresAt) {
        if (active != null && !active) {
            return DISABLED;
        }

        if (expiresAt != null && !expiresAt.isBlank()) {
            try {
                Instant expirationInstant = Instant.parse(expiresAt);
                if (Instant.now().isAfter(expirationInstant)) {
                    return EXPIRED;
                }
            } catch (Exception e) {
                log.warn("Erro ao converter data de expiração [expiresAt={}]: {}", expiresAt, e.getMessage());
                return EXPIRED;
            }
        }

        return ACTIVE;
    }
}