package com.example.learnhub.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.learnhub.common.autoconfigure.ConfigWatcherAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.cloud.autoconfigure.RefreshAutoConfiguration;

/**
 * Research R10: the watcher half of platform-common must load in a reactive application (the gateway
 * depends on it) and must not touch servlet classes.
 */
class ConfigWatcherAutoConfigurationTest {

    private static final AutoConfigurations AUTO_CONFIGS =
            AutoConfigurations.of(RefreshAutoConfiguration.class, ConfigWatcherAutoConfiguration.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AUTO_CONFIGS)
            .withPropertyValues("spring.application.name=echo-service");

    @Test
    @DisplayName("the watcher is registered when a ContextRefresher is present")
    void registeredWithRefresher() {
        runner.run(context -> assertThat(context).hasSingleBean(ConfigWatcher.class));
    }

    @Test
    @DisplayName("the watcher can be switched off, for services that are not config clients")
    void disabledByProperty() {
        runner.withPropertyValues("platform.config-watcher.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ConfigWatcher.class));
    }

    @Test
    @DisplayName("no ContextRefresher means no watcher, rather than a startup failure")
    void absentWithoutRefresher() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigWatcherAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ConfigWatcher.class);
                });
    }

    @Test
    @DisplayName("loads with servlet and Spring MVC classes hidden, proving it is reactive-safe")
    void loadsWithoutServletClasses() {
        runner.withClassLoader(new FilteredClassLoader(
                        jakarta.servlet.Filter.class,
                        org.springframework.web.servlet.DispatcherServlet.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ConfigWatcher.class);
                });
    }

    @Test
    @DisplayName("loads in a reactive web application, as the gateway needs")
    void loadsInReactiveApplication() {
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AUTO_CONFIGS)
                .withPropertyValues("spring.application.name=api-gateway")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ConfigWatcher.class);
                });
    }

    @Test
    @DisplayName("watcher properties bind from the platform.config-watcher prefix")
    void propertiesBind() {
        runner.withPropertyValues(
                        "platform.config-watcher.uri=http://config-server:9888",
                        "platform.config-watcher.interval=7s",
                        "platform.config-watcher.username=u",
                        "platform.config-watcher.password=p")
                .run(context -> {
                    ConfigWatcherProperties properties = context.getBean(ConfigWatcherProperties.class);
                    assertThat(properties.getUri()).isEqualTo("http://config-server:9888");
                    assertThat(properties.getInterval()).isEqualTo(java.time.Duration.ofSeconds(7));
                    assertThat(properties.getUsername()).isEqualTo("u");
                    assertThat(properties.getPassword()).isEqualTo("p");
                });
    }
}
