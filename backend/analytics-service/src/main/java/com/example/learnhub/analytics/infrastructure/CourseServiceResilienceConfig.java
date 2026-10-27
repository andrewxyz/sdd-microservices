package com.example.learnhub.analytics.infrastructure;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Duration;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The circuit breaker and time limit applied to calls to course-service (spec FR-016, research R7): the
 * same numbers as the other services' {@code resilience4j} settings (a window of 10 calls, open at 50%
 * failures once 5 calls have been seen, 10 s open, 3 trial calls, a 3 s limit per call).
 *
 * <p>Set in code as well as in {@code application.yml} because the factory only applies a named instance
 * from configuration when its name equals the circuit's id, and the id Feign gives each method is not the
 * client name; an unmatched name silently falls back to a 1 second limit and a window of 100 calls, which
 * would neither open in time nor give a slow course-service the time it needs. Every circuit created
 * here is the dashboard's course-service lookup, so the defaults are set for all of them.
 */
@Configuration(proxyBeanMethods = false)
public class CourseServiceResilienceConfig {

    @Bean
    public Customizer<Resilience4JCircuitBreakerFactory> courseServiceCircuitBreakers() {
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                        .slidingWindowSize(10)
                        .minimumNumberOfCalls(5)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(10))
                        .permittedNumberOfCallsInHalfOpenState(3)
                        .build())
                .timeLimiterConfig(TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofSeconds(3))
                        .cancelRunningFuture(true)
                        .build())
                .build());
    }
}
