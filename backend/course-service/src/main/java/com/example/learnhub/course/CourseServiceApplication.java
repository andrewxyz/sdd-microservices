package com.example.learnhub.course;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Registration with the registry, central configuration, health probes and structured logging all
 * come from dependencies and shared configuration, the same as every other LearnHub service.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CourseServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CourseServiceApplication.class, args);
    }
}
