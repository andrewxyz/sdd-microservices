package com.example.learnhub.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.course.support.AbstractCourseServiceIT;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * SC-004: with 10,000 published courses, a filtered, sorted, paginated catalog request returns within
 * 2 seconds (research.md R4 — an indexed status filter plus pagination, no dedicated search index
 * needed at this scale).
 */
class CatalogPerformanceIT extends AbstractCourseServiceIT {

    private static final int COURSE_COUNT = 10_000;
    private static boolean seeded = false;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void markUnseeded() {
        seeded = false;
    }

    private void seedOnce() {
        if (seeded) {
            return;
        }
        UUID categoryId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO categories (id, name, version) VALUES (?, ?, 0)",
                categoryId, "Perf-Category-" + categoryId);

        String insertSql = "INSERT INTO courses "
                + "(id, title, description, category_id, price_cents, currency, instructor_id, status, "
                + "published_at, created_at, updated_at, version) "
                + "VALUES (?, ?, ?, ?, ?, 'USD', ?, 'PUBLISHED', ?, ?, ?, 0)";
        Instant now = Instant.now();
        java.sql.Timestamp ts = java.sql.Timestamp.from(now);

        jdbcTemplate.batchUpdate(insertSql, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                ps.setObject(1, UUID.randomUUID());
                ps.setString(2, "Performance Course " + i);
                ps.setString(3, "Description " + i);
                ps.setObject(4, categoryId);
                ps.setInt(5, i % 1000);
                ps.setObject(6, UUID.randomUUID());
                ps.setTimestamp(7, ts);
                ps.setTimestamp(8, ts);
                ps.setTimestamp(9, ts);
            }

            @Override
            public int getBatchSize() {
                return COURSE_COUNT;
            }
        });
        seeded = true;
    }

    @Test
    void filteredSortedPaginatedListReturnsWithinTwoSeconds() throws Exception {
        seedOnce();

        Instant started = Instant.now();
        mockMvc.perform(get("/api/v1/courses")
                        .param("sort", "price_asc")
                        .param("free", "false")
                        .param("page", "5")
                        .param("size", "20"))
                .andExpect(status().isOk());
        Duration elapsed = Duration.between(started, Instant.now());

        assertThat(elapsed).as("SC-004 allows 2 seconds").isLessThan(Duration.ofSeconds(2));
    }
}
