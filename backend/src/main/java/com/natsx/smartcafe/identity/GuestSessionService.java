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
        List<GuestPrincipal> matches = jdbc.query("""
            SELECT s.guest_id,s.visit_id FROM guest_sessions s
            JOIN table_visits v ON v.id=s.visit_id
            WHERE s.token_hash=? AND s.revoked_at IS NULL AND s.expires_at > now()
              AND v.status='ACTIVE'
            """, (rs,row)-> new GuestPrincipal(rs.getObject("guest_id",UUID.class),
                rs.getObject("visit_id",UUID.class), hash), hash);
        return matches.isEmpty() ? null : matches.getFirst();
    }
}
