package com.example.learnhub.common.config;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;

/**
 * Reduces a config server reply to a hash of just the settings it delivers.
 *
 * <p>The reply also carries a {@code version} (the backend's revision marker) and a {@code state}
 * field, which can change without any setting changing. Hashing those would make the watcher refresh
 * for no reason, so they are left out. Property source order is kept because it decides precedence,
 * while key order inside a source is not significant and is normalised away.
 */
public final class ConfigSourcesHasher {

    private ConfigSourcesHasher() {
    }

    public static String hash(JsonNode configServerReply) {
        StringBuilder canonical = new StringBuilder();

        for (JsonNode source : configServerReply.path("propertySources")) {
            canonical.append(source.path("name").asText()).append('\u0000');

            JsonNode properties = source.path("source");
            List<String> keys = new ArrayList<>();
            Iterator<String> names = properties.fieldNames();
            while (names.hasNext()) {
                keys.add(names.next());
            }
            keys.sort(String::compareTo);

            for (String key : keys) {
                canonical.append(key).append('=').append(properties.get(key).asText()).append('\u0000');
            }
            canonical.append('\u0001');
        }

        return sha256(canonical.toString());
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
