package com.natsx.smartcafe.identity;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GuestSessionService {
    private final JdbcTemplate jdbc;
    public GuestSessionService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public GuestPrincipal authenticate(String token) {
        if (token == null || token.length() != 43) return null;
        String hash = SecureToken.sha256(token);
        String nowSql = com.natsx.smartcafe.common.db.JdbcSqlDialect.detect(jdbc)
            == com.natsx.smartcafe.common.db.JdbcSqlDialect.Vendor.MYSQL
            ? "UTC_TIMESTAMP(6)" : "now()";
        List<GuestPrincipal> matches = jdbc.query("""
            SELECT s.guest_id,s.visit_id FROM guest_sessions s
            JOIN table_visits v ON v.id=s.visit_id
            WHERE s.token_hash=? AND s.revoked_at IS NULL AND s.expires_at >
            """+nowSql+"""
              AND v.status='ACTIVE'
            """, (rs,row)-> new GuestPrincipal(
                com.natsx.smartcafe.common.db.JdbcUuid.read(rs,"guest_id"),
                com.natsx.smartcafe.common.db.JdbcUuid.read(rs,"visit_id"), hash), hash);
        return matches.isEmpty() ? null : matches.getFirst();
    }
}
