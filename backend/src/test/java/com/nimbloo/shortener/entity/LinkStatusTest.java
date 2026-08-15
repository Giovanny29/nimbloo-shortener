package com.nimbloo.shortener.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

class LinkStatusTest {

    @Test
    void calculateStatus_activeWithoutExpiration_returnsActive() {
        assertThat(LinkStatus.calculateStatus(true, null)).isEqualTo(LinkStatus.ACTIVE);
    }

    @Test
    void calculateStatus_activeWithFutureExpiration_returnsActive() {
        String future = Instant.now().plus(2, ChronoUnit.DAYS).toString();

        assertThat(LinkStatus.calculateStatus(true, future)).isEqualTo(LinkStatus.ACTIVE);
    }

    @Test
    void calculateStatus_disabled_returnsDisabled() {
        assertThat(LinkStatus.calculateStatus(false, null)).isEqualTo(LinkStatus.DISABLED);
    }

    @Test
    void calculateStatus_expired_returnsExpired() {
        String past = Instant.now().minus(1, ChronoUnit.HOURS).toString();

        assertThat(LinkStatus.calculateStatus(true, past)).isEqualTo(LinkStatus.EXPIRED);
    }

    @Test
    void calculateStatus_invalidExpiration_returnsExpired() {
        assertThat(LinkStatus.calculateStatus(true, "not-a-date")).isEqualTo(LinkStatus.EXPIRED);
    }
}