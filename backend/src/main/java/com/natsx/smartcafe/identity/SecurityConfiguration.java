package com.natsx.smartcafe.identity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // M1 has no login or guest token issuer. Deny every business route until M2.
        // Keep CSRF protection enabled for future session-based authenticated writes.
        return http
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/api/v1/system/status").permitAll()
                .requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**",
                    "/swagger-ui.html", "/swagger-ui/**").permitAll()
                .anyRequest().denyAll())
            .build();
    }
}
