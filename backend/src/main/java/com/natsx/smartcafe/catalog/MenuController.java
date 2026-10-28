package com.natsx.smartcafe.catalog;

import com.natsx.smartcafe.common.api.ApiEnvelope;
import com.natsx.smartcafe.common.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class MenuController {
    private final JdbcTemplate jdbc;
    public MenuController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record MenuItem(UUID id, String name, String category, long priceIdr) {}

    @GetMapping("/menu")
    public ApiEnvelope<List<MenuItem>> menu(HttpServletRequest request) {
        var items = jdbc.query("""
            SELECT i.id,i.name,c.name as category,i.base_price_idr FROM menu_items i
            JOIN menu_categories c ON c.id=i.category_id
            WHERE i.availability='AVAILABLE' AND c.active=true
            ORDER BY c.sort_order,i.name
            """, (rs,n)->new MenuItem(rs.getObject("id",UUID.class),rs.getString("name"),
                rs.getString("category"),rs.getLong("base_price_idr")));
        return ApiEnvelope.of(items,RequestIdFilter.get(request));
    }
}
