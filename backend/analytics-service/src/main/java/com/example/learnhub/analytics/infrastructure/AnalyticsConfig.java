package com.example.learnhub.analytics.infrastructure;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The clock all "today" and "generated at" values come from, so tests can fix it. Always UTC (spec FR-018). */
@Configuration(proxyBeanMethods = false)
public class AnalyticsConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
