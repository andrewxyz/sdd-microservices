package com.example.learnhub.common.feign;

import com.example.learnhub.common.correlation.CorrelationIds;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;

/**
 * Carries the correlation ID across a synchronous service-to-service call, so one client request can
 * still be followed end to end through the logs (constitution Principles V and VIII).
 */
public class CorrelationFeignInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        if (template.headers().containsKey(CorrelationIds.HEADER)) {
            return;
        }
        String correlationId = MDC.get(CorrelationIds.MDC_KEY);
        if (correlationId != null && !correlationId.isBlank()) {
            template.header(CorrelationIds.HEADER, correlationId);
        }
    }
}
