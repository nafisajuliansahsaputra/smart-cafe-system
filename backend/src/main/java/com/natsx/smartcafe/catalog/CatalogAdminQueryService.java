package com.natsx.smartcafe.catalog;

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
 * Bounded manager-only menu inventory projection. The legacy collection
 * endpoint is retained for compatibility, but the operational UI must not
 * silently omit products after the first 500.
 */
@Service
public class CatalogAdminQueryService {
    private static final int PAGE_SIZE = 20;
    private static final String FILTER = """
        (? = '' OR position(? in lower(m.name)) > 0
          OR position(? in lower(c.name)) > 0
          OR position(? in lower(m.availability)) > 0)
        """;
    private final JdbcTemplate jdbc;

    public CatalogAdminQueryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record ItemPage(int page, int pageSize, long total, boolean hasNext,
                           List<CatalogAdminService.Item> items) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ItemPage list(int page, String search) {
        if (page < 0 || page > 1000 || search == null || search.length() > 80 ||
            search.codePoints().anyMatch(Character::isISOControl)) {
            throw new ApiFailureException(HttpStatus.BAD_REQUEST, "INVALID_INPUT",
                "Invalid catalog page or search");
        }
        String q = search.strip().toLowerCase(Locale.ROOT);
        Long count = jdbc.queryForObject("""
            SELECT count(*)
            FROM menu_items m JOIN menu_categories c ON c.id=m.category_id
            WHERE """ + FILTER, Long.class, q, q, q, q);
        var items = jdbc.query("""
            SELECT m.id, m.category_id, m.name, m.base_price_idr,
                   m.availability, m.revision, m.description
            FROM menu_items m JOIN menu_categories c ON c.id=m.category_id
            WHERE """ + FILTER + """
            ORDER BY m.name, m.id
            LIMIT ? OFFSET ?
            """, (rs, index) -> new CatalogAdminService.Item(
                rs.getObject("id", UUID.class), rs.getObject("category_id", UUID.class),
                rs.getString("name"), rs.getLong("base_price_idr"),
                rs.getString("availability"), rs.getLong("revision"),
                rs.getString("description")),
            q, q, q, q, PAGE_SIZE + 1, page * PAGE_SIZE);
        boolean hasNext = items.size() > PAGE_SIZE;
        return new ItemPage(page, PAGE_SIZE, count == null ? 0 : count, hasNext,
            List.copyOf(items.subList(0, Math.min(items.size(), PAGE_SIZE))));
    }
}
