package com.natsx.smartcafe.audit;

import com.natsx.smartcafe.common.api.ApiFailureException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only staff audit projection. Never expose raw before/after payloads,
 * free-form reasons, tokens, or guest/customer information.
 */
@Service
public class AuditReviewService {
    private static final int PAGE_SIZE = 25;
    private final JdbcTemplate jdbc;

    public AuditReviewService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record AuditRow(UUID id, String operation, String resourceType,
                           UUID resourceId, String actor, Instant occurredAt) {}
    public record AuditPage(int page, int pageSize, boolean hasNext, List<AuditRow> items) {}

    @Transactional(readOnly = true)
    public AuditPage list(int page, String query) {
        if (page < 0 || page > 1000 || query == null || query.length() > 60 ||
            !query.matches("[A-Za-z0-9_-]*")) {
            throw new ApiFailureException(HttpStatus.BAD_REQUEST, "INVALID_INPUT",
                "Invalid audit search or page");
        }
        String keyword = query.toLowerCase(java.util.Locale.ROOT);
        int offset = page * PAGE_SIZE;
        // Bound every parameter. No dynamic SQL and no raw PII columns in this
        // projection. Actor IDs are used for the join but are not exposed.
        var params = new ArrayList<Object>();
        String sql = """
            SELECT a.id, a.operation, a.resource_type, a.resource_id,
                   COALESCE(s.login, a.actor_type) AS actor_display,
                   a.occurred_at
            FROM audit_logs a
            LEFT JOIN staff_users s ON a.actor_type='STAFF' AND s.id=a.actor_id
            """;
        if (!keyword.isEmpty()) {
            sql += """
                WHERE lower(a.operation) LIKE ? ESCAPE '!'
                   OR lower(a.resource_type) LIKE ? ESCAPE '!'
                   OR lower(COALESCE(s.login, a.actor_type)) LIKE ? ESCAPE '!'
                """;
            String pattern = "%" + keyword + "%";
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
        }
        sql += " ORDER BY a.occurred_at DESC, a.id DESC LIMIT ? OFFSET ?";
        params.add(PAGE_SIZE + 1);
        params.add(offset);
        List<AuditRow> rows = jdbc.query(sql, (rs, index) ->
            new AuditRow(rs.getObject("id", UUID.class), rs.getString("operation"),
                rs.getString("resource_type"), rs.getObject("resource_id", UUID.class),
                rs.getString("actor_display"), rs.getTimestamp("occurred_at").toInstant()),
            params.toArray());
        boolean hasNext = rows.size() > PAGE_SIZE;
        return new AuditPage(page, PAGE_SIZE, hasNext,
            List.copyOf(rows.subList(0, Math.min(rows.size(), PAGE_SIZE))));
    }
}
