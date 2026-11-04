package com.natsx.smartcafe.common.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;

/** Writes security filter errors using the same public error envelope as MVC. */
public final class SecurityErrorWriter {
    private SecurityErrorWriter() {}

    public static void write(HttpServletRequest request, HttpServletResponse response,
                             int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        // Every interpolated value is generated internally (request ID is a UUID).
        response.getWriter().write("{\"error\":{\"code\":\"" + code + "\",\"message\":\""
            + message + "\",\"requestId\":\"" + RequestIdFilter.get(request) + "\",\"details\":{}}}");
    }
}
