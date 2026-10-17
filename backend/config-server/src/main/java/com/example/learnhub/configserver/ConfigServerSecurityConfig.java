package com.example.learnhub.configserver;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Constitution Principle VII: outside the local profile these settings are credentials and
 * connection strings, so reading them requires a login. Health probes stay open under the single
 * exemption added in constitution v1.1.0, so container health checks still work.
 *
 * <p>Intentionally a separate class from the registry's: the two services use different credentials,
 * and sharing a security component between them would couple them for no gain.
 */
@Configuration(proxyBeanMethods = false)
public class ConfigServerSecurityConfig {

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
                // Clients authenticate with basic auth on every call and hold no session, so there is
                // no CSRF surface to protect.
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .build();
    }
}
