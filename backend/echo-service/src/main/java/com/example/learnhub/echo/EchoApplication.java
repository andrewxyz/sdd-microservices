package com.example.learnhub.echo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Deliberately bare. Registration with the registry, loading central configuration, health probes,
 * structured logging and the correlation ID all come from dependencies and shared configuration, so
 * there is nothing here to write (FR-029). {@code NoWiringCodeTest} enforces that.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class EchoApplication {

    public static void main(String[] args) {
        SpringApplication.run(EchoApplication.class, args);
    }
}
