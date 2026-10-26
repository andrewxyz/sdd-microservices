package com.example.learnhub.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.learnhub.review.api.CourseIdBatch;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit test for {@link CourseIdBatch} (FR-001, FR-002; data-model.md batch request rules). No Spring context.
 */
class ReviewSummariesValidationTest {

    @Test
    void acceptsASingleId() {
        UUID id = UUID.randomUUID();
        assertThat(CourseIdBatch.parse(id.toString())).containsExactly(id);
    }

    @Test
    void acceptsFiftyDistinctIdsInRequestOrder() {
        List<UUID> ids = IntStream.range(0, 50).mapToObj(i -> UUID.randomUUID()).toList();
        String raw = ids.stream().map(UUID::toString).collect(Collectors.joining(","));
        assertThat(CourseIdBatch.parse(raw)).containsExactlyElementsOf(ids);
    }

    @Test
    void collapsesDuplicatesKeepingFirstPosition() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertThat(CourseIdBatch.parse(a + "," + b + "," + a)).containsExactly(a, b);
    }

    @Test
    void rejectsNoIds() {
        assertThatThrownBy(() -> CourseIdBatch.parse("")).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        assertThatThrownBy(() -> CourseIdBatch.parse(null)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    void rejectsFiftyOneIds() {
        String raw = IntStream.range(0, 51).mapToObj(i -> UUID.randomUUID().toString())
                .collect(Collectors.joining(","));
        assertThatThrownBy(() -> CourseIdBatch.parse(raw)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    void rejectsAMalformedId() {
        assertThatThrownBy(() -> CourseIdBatch.parse(UUID.randomUUID() + ",not-a-uuid"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
    }
}
