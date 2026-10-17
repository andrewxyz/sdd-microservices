package com.example.learnhub.common.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.learnhub.common.correlation.CorrelationIdFilter;
import com.example.learnhub.common.error.ProblemDetailsAdvice;
import com.example.learnhub.common.feign.CorrelationFeignInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/**
 * Research R10: the servlet half must appear only in servlet applications. If it leaked into the
 * WebFlux gateway the context would fail to start, which is exactly what this guards.
 */
class ServletSupportAutoConfigurationTest {

    private static final AutoConfigurations AUTO_CONFIGS =
            AutoConfigurations.of(ServletSupportAutoConfiguration.class);

    @Test
    @DisplayName("a servlet application gets the correlation filter and the problem-details advice")
    void registeredInServletApplication() {
        new WebApplicationContextRunner()
                .withConfiguration(AUTO_CONFIGS)
                .run(context -> {
                    assertThat(context).hasSingleBean(CorrelationIdFilter.class);
                    assertThat(context).hasSingleBean(ProblemDetailsAdvice.class);
                });
    }

    @Test
    @DisplayName("a reactive application gets neither, so the gateway stays servlet-free")
    void absentInReactiveApplication() {
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AUTO_CONFIGS)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(CorrelationIdFilter.class);
                    assertThat(context).doesNotHaveBean(ProblemDetailsAdvice.class);
                });
    }

    @Test
    @DisplayName("a plain non-web application gets neither")
    void absentInNonWebApplication() {
        new ApplicationContextRunner()
                .withConfiguration(AUTO_CONFIGS)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(CorrelationIdFilter.class);
                });
    }

    @Test
    @DisplayName("with servlet classes absent the context still loads and registers nothing")
    void absentWhenServletClassesMissing() {
        new WebApplicationContextRunner()
                .withConfiguration(AUTO_CONFIGS)
                .withClassLoader(new FilteredClassLoader(jakarta.servlet.Filter.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(CorrelationIdFilter.class);
                });
    }

    @Test
    @DisplayName("the Feign interceptor appears only when Feign is on the classpath")
    void feignInterceptorIsConditional() {
        new WebApplicationContextRunner()
                .withConfiguration(AUTO_CONFIGS)
                .run(context -> assertThat(context).hasSingleBean(CorrelationFeignInterceptor.class));

        new WebApplicationContextRunner()
                .withConfiguration(AUTO_CONFIGS)
                .withClassLoader(new FilteredClassLoader(feign.RequestInterceptor.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(CorrelationFeignInterceptor.class);
                });
    }

    @Test
    @DisplayName("both auto-configurations are registered in the imports file")
    void listedInAutoConfigurationImports() throws Exception {
        var url = getClass().getClassLoader()
                .getResource("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        assertThat(url).as("auto-configuration imports file").isNotNull();

        String contents = new String(url.openStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(contents)
                .contains(ConfigWatcherAutoConfiguration.class.getName())
                .contains(ServletSupportAutoConfiguration.class.getName());
    }
}
