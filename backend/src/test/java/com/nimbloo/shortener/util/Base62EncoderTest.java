package com.nimbloo.shortener.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Base62EncoderTest {

    private final Base62Encoder encoder = new Base62Encoder();

    @Test
    void encode_positiveId_returnsAtLeastSevenCharacters() {
        String code = encoder.encode(125L);

        assertThat(code).hasSizeGreaterThanOrEqualTo(7);
    }

    @Test
    void encode_isDeterministicForSameId() {
        assertThat(encoder.encode(42L)).isEqualTo(encoder.encode(42L));
    }

    @Test
    void encode_differentIds_returnDifferentCodes() {
        assertThat(encoder.encode(1L)).isNotEqualTo(encoder.encode(2L));
    }

    @Test
    void encode_idLessThanOrEqualToZero_returnsZeroPaddedFallback() {
        assertThat(encoder.encode(0L)).isEqualTo("0000000");
        assertThat(encoder.encode(-5L)).isEqualTo("0000000");
    }

    @Test
    void encode_outputContainsOnlyBase62Characters() {
        String code = encoder.encode(1_000_000L);

        assertThat(code).matches("^[0-9a-zA-Z]{7,}$");
    }
}