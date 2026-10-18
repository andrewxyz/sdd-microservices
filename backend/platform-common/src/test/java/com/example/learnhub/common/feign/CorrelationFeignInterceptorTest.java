package com.example.learnhub.common.feign;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.learnhub.common.correlation.CorrelationIds;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Constitution Principle V: a correlation ID must survive a service-to-service call. No Feign client
 * exists yet in this feature, so this interceptor is the contract the first one will rely on.
 */
class CorrelationFeignInterceptorTest {

    private final CorrelationFeignInterceptor interceptor = new CorrelationFeignInterceptor();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("the current correlation id is forwarded on the outgoing request")
    void forwardsCurrentId() {
        MDC.put(CorrelationIds.MDC_KEY, "outgoing-id-1234");
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers().get(CorrelationIds.HEADER)).containsExactly("outgoing-id-1234");
    }

    @Test
    @DisplayName("nothing is added when there is no correlation id in scope")
    void addsNothingWithoutId() {
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers()).doesNotContainKey(CorrelationIds.HEADER);
    }

    @Test
    @DisplayName("an existing header is not duplicated")
    void doesNotDuplicateExistingHeader() {
        MDC.put(CorrelationIds.MDC_KEY, "outgoing-id-1234");
        RequestTemplate template = new RequestTemplate();
        template.header(CorrelationIds.HEADER, "already-set-1234");

        interceptor.apply(template);

        assertThat(template.headers().get(CorrelationIds.HEADER)).containsExactly("already-set-1234");
    }
}
