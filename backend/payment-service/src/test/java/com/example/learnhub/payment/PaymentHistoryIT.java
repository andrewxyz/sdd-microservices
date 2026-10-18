package com.example.learnhub.payment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.common.identity.IdentityHeaders;
import com.example.learnhub.payment.support.AbstractPaymentServiceIT;
import com.example.learnhub.payment.support.CourseServiceStub;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** US5 (FR-017, FR-019, FR-020): a user's own payment history, never anyone else's. */
class PaymentHistoryIT extends AbstractPaymentServiceIT {

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

    private String checkout(UUID userId, UUID courseId, String idempotencyKey) throws Exception {
        String body = mockMvc.perform(post("/api/v1/checkouts")
                        .header(IdentityHeaders.USER_ID, userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"courseId":"%s","idempotencyKey":"%s"}
                                """.formatted(courseId, idempotencyKey)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body).get("id").asText();
    }

    @Test
    void usersSeeOnlyTheirOwnPaymentHistory() throws Exception {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        UUID courseForA = UUID.randomUUID();
        UUID courseForB = UUID.randomUUID();
        COURSE_SERVICE.published(courseForA, UUID.randomUUID(), 0);
        COURSE_SERVICE.published(courseForB, UUID.randomUUID(), 0);

        checkout(userA, courseForA, "history-a-1");
        checkout(userB, courseForB, "history-b-1");

        mockMvc.perform(get("/api/v1/payments").header(IdentityHeaders.USER_ID, userA.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].courseId").value(courseForA.toString()));

        mockMvc.perform(get("/api/v1/payments").header(IdentityHeaders.USER_ID, userB.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].courseId").value(courseForB.toString()));
    }

    @Test
    void aPaymentIdBelongingToSomeoneElseIsAnIdentical404() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        COURSE_SERVICE.published(courseId, UUID.randomUUID(), 0);

        String paymentId = checkout(owner, courseId, "owned-by-owner-1");

        mockMvc.perform(get("/api/v1/payments/" + paymentId).header(IdentityHeaders.USER_ID, owner.toString()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/payments/" + paymentId).header(IdentityHeaders.USER_ID, stranger.toString()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/payments/" + UUID.randomUUID()).header(IdentityHeaders.USER_ID, stranger.toString()))
                .andExpect(status().isNotFound());
    }
}
