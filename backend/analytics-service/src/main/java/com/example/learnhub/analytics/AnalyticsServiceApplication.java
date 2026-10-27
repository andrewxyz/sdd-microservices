package com.example.learnhub.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Registration with the registry, central configuration, health probes and structured logging all
 * come from dependencies and shared configuration, the same as every other LearnHub service.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableFeignClients
public class AnalyticsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnalyticsServiceApplication.class, args);
    }
}
