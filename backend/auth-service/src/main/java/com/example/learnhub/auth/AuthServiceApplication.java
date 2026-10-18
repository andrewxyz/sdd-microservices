package com.example.learnhub.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Registration with the registry, central configuration, health probes and structured logging all
 * come from dependencies and shared configuration (see {@code application.yml} and
 * {@code backend/config-repo/application.yml}), the same as every other LearnHub service.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
