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

public final class GuestBearerFilter extends OncePerRequestFilter {
    private final GuestSessionService sessions;

    public GuestBearerFilter(GuestSessionService sessions) { this.sessions = sessions; }

    // Servlet async dispatch re-enters the stateless security filter chain.
    // Reauthenticate the original Bearer on ASYNC response dispatch instead
    // of accidentally returning 401 or bypassing authorization.
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/api/v1/me/")
            || request.getRequestURI().equals("/api/v1/orders")) {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                GuestPrincipal principal = sessions.authenticate(header.substring(7));
                if (principal != null) {
                    var context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(new UsernamePasswordAuthenticationToken(
                        principal, null, java.util.List.of(new SimpleGrantedAuthority("GUEST"))));
                    SecurityContextHolder.setContext(context);
                }
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
