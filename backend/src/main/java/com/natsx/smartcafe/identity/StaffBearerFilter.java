package com.natsx.smartcafe.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public final class StaffBearerFilter extends OncePerRequestFilter {
    private final StaffAuthService auth;

    public StaffBearerFilter(StaffAuthService auth) { this.auth = auth; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            StaffPrincipal principal = auth.authenticate(header.substring(7));
            if (principal != null) {
                var authorities = principal.roles().stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
                var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
