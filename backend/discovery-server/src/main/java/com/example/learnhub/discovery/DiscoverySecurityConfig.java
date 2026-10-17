package com.example.learnhub.discovery;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Constitution Principle VII: the registry requires basic auth everywhere except the local profile.
 *
 * <p>The one exemption, added in constitution v1.1.0, is {@code /actuator/health/**}: Docker health
 * checks have no credentials, so leaving probes open is what lets the container report healthy
 * outside {@code local}. Everything else, including the dashboard and the registry API, is closed.
 *
 * <p>Not shared with the config server on purpose: the two use different credentials, and a shared
 * security component between infrastructure services would be the kind of coupling Principle II
 * keeps out of shared libraries.
 */
@Configuration(proxyBeanMethods = false)
public class DiscoverySecurityConfig {

    @Bean
    @Profile("local")
    SecurityFilterChain localChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .build();
    }

    @Bean
    @Profile("!local")
    SecurityFilterChain protectedChain(HttpSecurity http) throws Exception {
        return http
                // Eureka clients register over POST/PUT without a CSRF token; they authenticate with
                // basic auth instead, so CSRF protection buys nothing here and would break them.
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .build();
    }
}
