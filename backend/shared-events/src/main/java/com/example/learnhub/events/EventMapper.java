package com.example.learnhub.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Builds the Jackson mapper the envelope is written and read with, so every publisher and consumer
 * agrees on the wire format regardless of how their own application mapper is configured.
 *
 * <p>Instants are written as ISO-8601 UTC strings ending in {@code Z}, not epoch numbers, and unknown
 * fields are rejected so a producer cannot silently add one.
 */
public final class EventMapper {

    private EventMapper() {
    }

    public static ObjectMapper create() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .setSerializationInclusion(JsonInclude.Include.ALWAYS);
    }
}
