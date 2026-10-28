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
    org.springframework.security.crypto.password.PasswordEncoder passwordEncoder() {
        return new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, StaffAuthService staffAuth, GuestSessionService guestAuth) throws Exception {
        // Only explicit Authorization: Bearer headers authenticate staff.
        // No browser cookies are accepted; adding cookie auth requires CSRF protection.
        return http
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(AbstractHttpConfigurer::disable)
            .addFilterBefore(new StaffBearerFilter(staffAuth),
                org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(new GuestBearerFilter(guestAuth), StaffBearerFilter.class)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, ex) ->
                    com.natsx.smartcafe.common.api.SecurityErrorWriter.write(
                        request, response, 401, "UNAUTHENTICATED", "Authentication required"))
                .accessDeniedHandler((request, response, ex) ->
                    com.natsx.smartcafe.common.api.SecurityErrorWriter.write(
                        request, response, 403, "FORBIDDEN", "Access denied")))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/api/v1/system/status").permitAll()
                .requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**",
                    "/swagger-ui.html", "/swagger-ui/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/staff/auth/login", "/api/v1/table-visits/join").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/tables/*/entry", "/api/v1/menu").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/orders").hasAuthority("GUEST")
                .requestMatchers("/api/v1/me/**").hasAuthority("GUEST")
                .requestMatchers("/api/v1/staff/auth/me", "/api/v1/staff/auth/logout").authenticated()
                .requestMatchers("/api/v1/ops/**").hasAnyRole("CASHIER", "MANAGER", "ADMIN")
                .requestMatchers("/api/v1/waiter/**").hasAnyRole("WAITER", "CASHIER", "MANAGER", "ADMIN")
                .requestMatchers("/api/v1/kitchen/**").hasAnyRole("KITCHEN", "MANAGER", "ADMIN")
                .anyRequest().denyAll())
            .build();
    }
}
