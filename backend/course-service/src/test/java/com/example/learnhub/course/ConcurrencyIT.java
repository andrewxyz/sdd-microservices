package com.example.learnhub.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.example.learnhub.common.identity.IdentityHeaders;
import com.example.learnhub.course.domain.Course;
import com.example.learnhub.course.infrastructure.CourseRepository;
import com.example.learnhub.course.support.AbstractCourseServiceIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-025, SC-006: when two updates to the same course are both submitted against the version the
 * course had before either applied, exactly one succeeds and the other is refused with a conflict —
 * never a silent overwrite. Simulated as two edits racing against one read, rather than literal
 * parallel threads, since the property under test is "stale version is always rejected," not timing.
 */
class ConcurrencyIT extends AbstractCourseServiceIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CourseRepository courses;

    @Transactional
    Course seed() {
        Course course = new Course(UUID.randomUUID(), UUID.randomUUID(), Instant.now());
        course.setTitle("Concurrency Target");
        course.setDescription("D");
        return courses.save(course);
    }

    @Test
    void exactlyOneOfTwoConcurrentEditsSucceedsAcrossTenTrials() throws Exception {
        int successes = 0;
        int conflicts = 0;

        for (int trial = 0; trial < 10; trial++) {
            Course course = seed();
            String ifMatch = "\"" + course.getVersion() + "\"";
            UUID owner = course.getInstructorId();
            String editA = "{\"title\":\"Edit A trial " + trial + "\",\"description\":\"D\",\"categoryId\":null,\"priceCents\":null}";
            String editB = "{\"title\":\"Edit B trial " + trial + "\",\"description\":\"D\",\"categoryId\":null,\"priceCents\":null}";

            int statusA = mockMvc.perform(put("/api/v1/courses/" + course.getId())
                            .header(IdentityHeaders.USER_ID, owner.toString())
                            .header(IdentityHeaders.USER_ROLE, "INSTRUCTOR")
                            .header(HttpHeaders.IF_MATCH, ifMatch)
                            .contentType(MediaType.APPLICATION_JSON).content(editA))
                    .andReturn().getResponse().getStatus();
            int statusB = mockMvc.perform(put("/api/v1/courses/" + course.getId())
                            .header(IdentityHeaders.USER_ID, owner.toString())
                            .header(IdentityHeaders.USER_ROLE, "INSTRUCTOR")
                            .header(HttpHeaders.IF_MATCH, ifMatch)
                            .contentType(MediaType.APPLICATION_JSON).content(editB))
                    .andReturn().getResponse().getStatus();

            assertThat(statusA == 200 || statusB == 200).as("trial %d: exactly one must succeed", trial).isTrue();
            assertThat(statusA == 409 || statusB == 409).as("trial %d: exactly one must conflict", trial).isTrue();
            if (statusA == 200) {
                successes++;
            } else {
                conflicts++;
            }
            if (statusB == 200) {
                successes++;
            } else {
                conflicts++;
            }
        }

        assertThat(successes).isEqualTo(10);
        assertThat(conflicts).isEqualTo(10);
    }
}
