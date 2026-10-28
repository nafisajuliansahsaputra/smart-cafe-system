package com.natsx.smartcafe.common.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = "scs.requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader("X-Request-Id", requestId);
        chain.doFilter(request, response);
    }

    public static String get(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof String id ? id : "unknown";
    }
}
