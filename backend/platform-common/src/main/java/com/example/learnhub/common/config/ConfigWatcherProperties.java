package com.example.learnhub.common.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings of the polling config watcher (research R4). */
@ConfigurationProperties("platform.config-watcher")
public class ConfigWatcherProperties {

    /** Turn off for services that are not config clients, such as the discovery and config servers. */
    private boolean enabled = true;

    /**
     * How often to ask the config server for the current settings. Five seconds keeps an edit under
     * the ten seconds FR-016 allows, including the refresh itself.
     */
    private Duration interval = Duration.ofSeconds(5);

    /** Config server base URL; defaults to the same value the config client uses. */
    private String uri;

    private String username;

    private String password;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getInterval() {
        return interval;
    }

    public void setInterval(Duration interval) {
        this.interval = interval;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
