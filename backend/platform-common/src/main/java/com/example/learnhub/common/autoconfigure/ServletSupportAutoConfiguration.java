package com.example.learnhub.common.autoconfigure;

import com.example.learnhub.common.correlation.CorrelationIdFilter;
import com.example.learnhub.common.error.ProblemDetailsAdvice;
import com.example.learnhub.common.feign.CorrelationFeignInterceptor;
import jakarta.servlet.Filter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * The servlet-only half of platform-common: correlation ID in MDC, RFC 7807 errors, and correlation
 * forwarding on Feign calls. A new service gets all three by depending on this module, which is what
 * lets FR-029 hold ("no service-specific code").
 *
 * <p>Guarded twice over, by web application type and by class presence, because the WebFlux gateway
 * also depends on this module and must not pick any of it up (research R10).
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({Filter.class, DispatcherServlet.class})
public class ServletSupportAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }

    @Bean
    @ConditionalOnMissingBean
    public ProblemDetailsAdvice problemDetailsAdvice() {
        return new ProblemDetailsAdvice();
    }

    /** Only when the service actually uses Feign; feign-core is an optional dependency. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(feign.RequestInterceptor.class)
    static class FeignSupport {

        @Bean
        @ConditionalOnMissingBean
        CorrelationFeignInterceptor correlationFeignInterceptor() {
            return new CorrelationFeignInterceptor();
        }
    }
}
