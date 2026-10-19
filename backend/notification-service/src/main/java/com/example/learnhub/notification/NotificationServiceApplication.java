package com.example.learnhub.notification;

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
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
