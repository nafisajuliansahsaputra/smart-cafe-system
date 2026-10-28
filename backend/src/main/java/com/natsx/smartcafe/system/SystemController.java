package com.natsx.smartcafe.system;

import com.natsx.smartcafe.common.api.ApiEnvelope;
import com.natsx.smartcafe.common.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {
    private final JdbcTemplate jdbc;

    public SystemController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Status(String service, String database, int configuredTables) {}

    @GetMapping("/status")
    public ApiEnvelope<Status> status(HttpServletRequest request) {
        // This query fails when the database is unavailable OR baseline migration did not run.
        Integer tables = jdbc.queryForObject("SELECT COUNT(*) FROM cafe_tables", Integer.class);
        return ApiEnvelope.of(new Status("smart-cafe-api", "connected", tables == null ? 0 : tables),
            RequestIdFilter.get(request));
    }
}
