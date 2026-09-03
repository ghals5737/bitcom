package com.bitcom.portal.common;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;

@Component
public class SecureTokenGenerator implements TokenGenerator {
    private static final String LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String SPECIALS = "!@#$%*";

    private final SecureRandom random = new SecureRandom();

    @Override
    public String sessionId() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    @Override
    public String temporaryPassword() {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 9; i++) sb.append(pick(LETTERS));
        sb.append(pick(DIGITS)).append(pick(SPECIALS)).append(pick(DIGITS));
        return sb.toString();
    }

    private char pick(String s) {
        return s.charAt(random.nextInt(s.length()));
    }
}
