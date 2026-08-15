package com.nimbloo.shortener.util;

import org.springframework.stereotype.Component;

@Component
public class Base62Encoder {

    private static final String BASE62_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final long MULTIPLIER = 2654435761L; // Constante Multiplicativa de Knuth

    public String encode(long id) {
        if (id <= 0) {
            return "0000000";
        }

        long scrambled = scramble(id);

        StringBuilder sb = new StringBuilder();
        while (scrambled > 0) {
            int remainder = (int) (scrambled % 62);
            sb.append(BASE62_ALPHABET.charAt(remainder));
            scrambled /= 62;
        }

        while (sb.length() < 7) {
            sb.append('0');
        }

        return sb.reverse().toString();
    }

    private long scramble(long id) {
        long x = id * MULTIPLIER;
        x ^= (x >>> 13);
        x ^= (x << 17);
        x ^= (x >>> 5);
        return x & 0x7FFFFFFFFFFFFFFFL; 
    }
}