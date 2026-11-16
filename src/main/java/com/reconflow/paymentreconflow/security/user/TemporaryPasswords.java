package com.reconflow.paymentreconflow.security.user;

import java.security.SecureRandom;

/**
 * Server-generated temporary passwords: 16 characters from SecureRandom over an alphabet without
 * look-alikes (0/O, 1/l/I), about 94 bits. Returned once in a response body (or printed once by the
 * bootstrap runner) and never logged; the user must replace it at first login.
 */
public final class TemporaryPasswords {

    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int LENGTH = 16;

    private TemporaryPasswords() {}

    public static String generate() {
        char[] out = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            out[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(out);
    }
}
