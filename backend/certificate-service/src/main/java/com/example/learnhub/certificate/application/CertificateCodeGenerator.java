package com.example.learnhub.certificate.application;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * Produces a 16-character verification code from a fixed, unambiguous alphabet (data-model.md) —
 * excludes {@code 0/O}, {@code 1/I/L} and similar look-alikes, so a code read aloud or hand-typed is
 * never ambiguous. The caller retries on a unique-constraint violation (vanishingly unlikely given the
 * keyspace, handled defensively).
 */
@Component
public class CertificateCodeGenerator {

    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final int CODE_LENGTH = 16;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
