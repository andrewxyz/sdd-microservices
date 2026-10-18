package com.example.learnhub.payment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.common.identity.IdentityHeaders;
import com.example.learnhub.payment.support.AbstractPaymentServiceIT;
import com.example.learnhub.payment.support.CourseServiceStub;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * US1 (FR-001 to FR-006), US1 scenarios 4-5 (FR-003, FR-004), US2 (FR-007, FR-008). Every checkout goes
 * through a real {@code CourseLookupClient} call to a WireMock stand-in for course-service, not a mock
 * of {@code CheckoutService} itself (constitution Principle VI: real integrations, not mocked collaborators).
 */
class CheckoutIT extends AbstractPaymentServiceIT {

    private static final CourseServiceStub COURSE_SERVICE = new CourseServiceStub();

    static {
        COURSE_SERVICE.start();
    }

    @DynamicPropertySource
    static void courseServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.course-service.url", COURSE_SERVICE::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        COURSE_SERVICE.stop();
    }

    @Autowired
    private MockMvc mockMvc;

    private static String checkoutBody(UUID courseId, String idempotencyKey, String paymentToken) {
        String token = paymentToken == null ? "null" : "\"" + paymentToken + "\"";
        return """
                {"courseId":"%s","idempotencyKey":"%s","paymentToken":%s}
                """.formatted(courseId, idempotencyKey, token);
    }

    @Test
    void freeCourseChecksOutImmediatelyWithNoPaymentToken() throws Exception {
        UUID courseId = UUID.randomUUID();
        COURSE_SERVICE.published(courseId, UUID.randomUUID(), 0);

        mockMvc.perform(post("/api/v1/checkouts")
                        .header(IdentityHeaders.USER_ID, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody(courseId, "free-1", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.amountCents").value(0))
                .andExpect(jsonPath("$.message").value("Enrollment will follow shortly."));
    }

    @Test
    void paidCourseChecksOutWithTheSuccessTokenForTheCatalogPrice() throws Exception {
        UUID courseId = UUID.randomUUID();
        COURSE_SERVICE.published(courseId, UUID.randomUUID(), 1999);

        mockMvc.perform(post("/api/v1/checkouts")
                        .header(IdentityHeaders.USER_ID, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody(courseId, "paid-1", "sim-success")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.amountCents").value(1999));
    }

    @Test
    void instructorCannotCheckOutTheirOwnCourse() throws Exception {
        UUID courseId = UUID.randomUUID();
        UUID instructorId = UUID.randomUUID();
        COURSE_SERVICE.published(courseId, instructorId, 500);

        mockMvc.perform(post("/api/v1/checkouts")
                        .header(IdentityHeaders.USER_ID, instructorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody(courseId, "own-course-1", "sim-success")))
                .andExpect(status().isForbidden());
    }

    @Test
    void alreadyEnrolledUserCannotPayTwice() throws Exception {
        UUID courseId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        COURSE_SERVICE.published(courseId, UUID.randomUUID(), 500);

        mockMvc.perform(post("/api/v1/checkouts")
                        .header(IdentityHeaders.USER_ID, userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody(courseId, "first-pay", "sim-success")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/checkouts")
                        .header(IdentityHeaders.USER_ID, userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody(courseId, "second-pay", "sim-success")))
                .andExpect(status().isConflict());
    }

    @Test
    void unpublishedCourseCannotBeCheckedOut() throws Exception {
        UUID courseId = UUID.randomUUID();
        COURSE_SERVICE.notPublished(courseId, UUID.randomUUID(), "DRAFT");

        mockMvc.perform(post("/api/v1/checkouts")
                        .header(IdentityHeaders.USER_ID, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody(courseId, "draft-1", "sim-success")))
                .andExpect(status().isConflict());
    }

    @Test
    void unknownCourseIsRefusedWith404() throws Exception {
        UUID courseId = UUID.randomUUID();
        COURSE_SERVICE.notFound(courseId);

        mockMvc.perform(post("/api/v1/checkouts")
                        .header(IdentityHeaders.USER_ID, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody(courseId, "unknown-1", "sim-success")))
                .andExpect(status().isNotFound());
    }

    @Test
    void decliningTokenResolvesToDeclinedWithNoEnrollment() throws Exception {
        UUID courseId = UUID.randomUUID();
        COURSE_SERVICE.published(courseId, UUID.randomUUID(), 500);

        mockMvc.perform(post("/api/v1/checkouts")
                        .header(IdentityHeaders.USER_ID, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody(courseId, "decline-1", "sim-decline")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.failureReason").isNotEmpty())
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void unavailableTokenResolvesToFailedWithNoEnrollment() throws Exception {
        UUID courseId = UUID.randomUUID();
        COURSE_SERVICE.published(courseId, UUID.randomUUID(), 500);

        mockMvc.perform(post("/api/v1/checkouts")
                        .header(IdentityHeaders.USER_ID, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody(courseId, "unavailable-1", "sim-unavailable")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").isNotEmpty());
    }

    @Test
    void aDeclineDoesNotPermanentlyBlockATryAgain() throws Exception {
        UUID courseId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        COURSE_SERVICE.published(courseId, UUID.randomUUID(), 500);

        mockMvc.perform(post("/api/v1/checkouts")
                        .header(IdentityHeaders.USER_ID, userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody(courseId, "retry-after-decline-1", "sim-decline")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DECLINED"));

        mockMvc.perform(post("/api/v1/checkouts")
                        .header(IdentityHeaders.USER_ID, userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody(courseId, "retry-after-decline-2", "sim-success")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }
}
