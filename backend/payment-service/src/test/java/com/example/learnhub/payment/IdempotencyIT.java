package com.example.learnhub.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.common.identity.IdentityHeaders;
import com.example.learnhub.payment.domain.PaymentStatus;
import com.example.learnhub.payment.infrastructure.PaymentRepository;
import com.example.learnhub.payment.support.AbstractPaymentServiceIT;
import com.example.learnhub.payment.support.CourseServiceStub;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** US4 (FR-010 to FR-012, SC-003): safe retries under a client-supplied idempotency key. */
class IdempotencyIT extends AbstractPaymentServiceIT {

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

    @Autowired
    private PaymentRepository payments;

    private static String checkoutBody(UUID courseId, String idempotencyKey, String paymentToken) {
        return """
                {"courseId":"%s","idempotencyKey":"%s","paymentToken":"%s"}
                """.formatted(courseId, idempotencyKey, paymentToken);
    }

    @Test
    void replayingTheSameRequestReturnsTheOriginalPaymentUnchanged() throws Exception {
        UUID courseId = UUID.randomUUID();
        COURSE_SERVICE.published(courseId, UUID.randomUUID(), 500);
        UUID userId = UUID.randomUUID();

        String firstBody = mockMvc.perform(post("/api/v1/checkouts")
                        .header(IdentityHeaders.USER_ID, userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody(courseId, "replay-1", "sim-success")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String secondBody = mockMvc.perform(post("/api/v1/checkouts")
                        .header(IdentityHeaders.USER_ID, userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody(courseId, "replay-1", "sim-success")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        ObjectMapper mapper = new ObjectMapper();
        String firstId = mapper.readTree(firstBody).get("id").asText();
        String secondId = mapper.readTree(secondBody).get("id").asText();
        assertThat(secondId).isEqualTo(firstId);
        assertThat(payments.findAll().stream().filter(p -> "replay-1".equals(p.getIdempotencyKey())).count())
                .isEqualTo(1);
    }

    @Test
    void reusingTheSameKeyWithADifferentRequestIsRefused() throws Exception {
        UUID courseA = UUID.randomUUID();
        UUID courseB = UUID.randomUUID();
        COURSE_SERVICE.published(courseA, UUID.randomUUID(), 500);
        COURSE_SERVICE.published(courseB, UUID.randomUUID(), 500);
        UUID userId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/checkouts")
                        .header(IdentityHeaders.USER_ID, userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody(courseA, "conflict-1", "sim-success")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/checkouts")
                        .header(IdentityHeaders.USER_ID, userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody(courseB, "conflict-1", "sim-success")))
                .andExpect(status().isConflict());
    }

    @Test
    void twentySimultaneousIdenticalCheckoutsProduceExactlyOnePayment() throws Exception {
        UUID courseId = UUID.randomUUID();
        COURSE_SERVICE.published(courseId, UUID.randomUUID(), 500);
        UUID userId = UUID.randomUUID();
        int concurrency = 20;

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        mockMvc.perform(post("/api/v1/checkouts")
                                .header(IdentityHeaders.USER_ID, userId.toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(checkoutBody(courseId, "concurrent-1", "sim-success")));
                    } catch (Exception ignored) {
                        // A losing racer may see a transient error; the assertion below is what matters.
                    }
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            for (java.util.concurrent.Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }

        Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> {
            long succeeded = payments.findAll().stream()
                    .filter(p -> p.getCourseId().equals(courseId) && p.getUserId().equals(userId)
                            && p.getStatus() == PaymentStatus.SUCCEEDED)
                    .count();
            assertThat(succeeded).isEqualTo(1);
        });
    }
}
