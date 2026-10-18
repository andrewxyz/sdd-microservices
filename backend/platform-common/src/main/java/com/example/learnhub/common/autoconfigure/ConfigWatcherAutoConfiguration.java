package com.example.learnhub.common.autoconfigure;

import com.example.learnhub.common.config.ConfigWatcher;
import com.example.learnhub.common.config.ConfigWatcherProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.autoconfigure.RefreshAutoConfiguration;
import org.springframework.cloud.context.refresh.ContextRefresher;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Registers the polling config watcher (research R4), giving every config client live updates within
 * the ten seconds FR-016 allows, with no per-service code.
 *
 * <p>This half of platform-common must stay free of servlet and WebFlux types so the WebFlux gateway
 * can depend on it; {@code ConfigWatcherAutoConfigurationTest} enforces that with a filtered class
 * loader.
 */
@AutoConfiguration(after = RefreshAutoConfiguration.class)
@EnableConfigurationProperties(ConfigWatcherProperties.class)
@ConditionalOnProperty(prefix = "platform.config-watcher", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class ConfigWatcherAutoConfiguration {

    @Bean(destroyMethod = "stop")
    @ConditionalOnBean(ContextRefresher.class)
    @ConditionalOnMissingBean
    public ConfigWatcher configWatcher(ConfigWatcherProperties properties, Environment environment,
            ContextRefresher refresher,
            @Value("${spring.application.name:application}") String applicationName) {

        if (properties.getUri() == null || properties.getUri().isBlank()) {
            properties.setUri(environment.getProperty("spring.cloud.config.uri",
                    environment.getProperty("CONFIG_SERVER_URL", "")));
        }
        if (properties.getUsername() == null) {
            properties.setUsername(environment.getProperty("spring.cloud.config.username"));
        }
        if (properties.getPassword() == null) {
            properties.setPassword(environment.getProperty("spring.cloud.config.password"));
        }

        String profiles = activeProfiles(environment);
        ConfigWatcher watcher = new ConfigWatcher(properties, applicationName, profiles, refresher::refresh);
        if (properties.getUri() != null && !properties.getUri().isBlank()) {
            watcher.start();
        }
        return watcher;
    }

    private static String activeProfiles(Environment environment) {
        String[] active = environment.getActiveProfiles();
        return active.length == 0 ? "default" : String.join(",", active);
    }
}
