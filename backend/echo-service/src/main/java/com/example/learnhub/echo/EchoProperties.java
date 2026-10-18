package com.example.learnhub.echo;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The echo service's own settings, delivered by the config server.
 *
 * <p>Deliberately no {@code @RefreshScope}: Spring Cloud rebinds {@code @ConfigurationProperties}
 * on a refresh by itself, and the shared config watcher triggers that refresh. A new service gets
 * live settings by binding properties the ordinary way and writing no refresh code (FR-029).
 */
@ConfigurationProperties("echo")
public class EchoProperties {

    /** Proves a central setting reaches a running service; changed to demonstrate live updates. */
    private String greeting = "Hello from LearnHub";

    public String getGreeting() {
        return greeting;
    }

    public void setGreeting(String greeting) {
        this.greeting = greeting;
    }
}
