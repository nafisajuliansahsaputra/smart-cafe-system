package com.natsx.smartcafe.tables;

import com.natsx.smartcafe.common.api.ApiFailureException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manager/admin-only physical table inventory. Unlike the waiter floor
 * dashboard this read model is paginated at the database, not capped at 250.
 * Never return join proofs, guest information or staff access tokens.
 */
@Service
public class TableRegistryQueryService {
    private static final int PAGE_SIZE = 25;
    private final JdbcTemplate jdbc;

    public TableRegistryQueryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record TableRow(UUID tableId, String tableCode, String publicAlias,
                           int capacity, String tableStatus, boolean activeVisit) {}
    public record RegistryPage(int page, int pageSize, boolean hasNext,
                               long total, List<TableRow> items) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RegistryPage list(int page, String query) {
        if (page < 0 || page > 1000 || query == null || query.length() > 60 ||
            !query.matches("[A-Za-z0-9_-]*")) {
            throw new ApiFailureException(HttpStatus.BAD_REQUEST, "INVALID_INPUT",
                "Invalid registry search or page");
        }
        String keyword = query.toLowerCase(Locale.ROOT);
        // POSITION treats underscores and percent signs as literals, not SQL wildcards.
        // No dynamic SQL. The count and rows share the same repeatable-read view
        // when the surrounding database transaction provides it.
        String predicate = """
            (? = '' OR position(? in lower(t.table_code)) > 0
              OR position(? in lower(t.public_alias)) > 0)
            """;
        Long count = jdbc.queryForObject(
            "SELECT count(*) FROM cafe_tables t WHERE " + predicate, Long.class,
            keyword, keyword, keyword);
        List<TableRow> rows = jdbc.query("""
            SELECT t.id, t.table_code, t.public_alias, t.capacity, t.status,
                   EXISTS (SELECT 1 FROM table_visits v
                           WHERE v.table_id=t.id AND v.status IN ('ACTIVE','CLOSING'))
                     AS active_visit
            FROM cafe_tables t
            WHERE """ + predicate + """
            ORDER BY t.table_code, t.id
            LIMIT ? OFFSET ?
            """, (rs, index) -> new TableRow(
                com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "id"), rs.getString("table_code"),
                rs.getString("public_alias"), rs.getInt("capacity"),
                rs.getString("status"), rs.getBoolean("active_visit")),
            keyword, keyword, keyword, PAGE_SIZE + 1, page * PAGE_SIZE);
        boolean hasNext = rows.size() > PAGE_SIZE;
        return new RegistryPage(page, PAGE_SIZE, hasNext, count == null ? 0 : count,
            List.copyOf(rows.subList(0, Math.min(rows.size(), PAGE_SIZE))));
    }
}
