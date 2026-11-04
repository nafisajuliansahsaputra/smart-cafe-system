package com.natsx.smartcafe.identity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Database-backed, revocable sessions; no auth secrets are written to logs. */
@Service
public class StaffAuthService {
    private static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final Duration SESSION_TTL = Duration.ofHours(8);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final String dummyHash;

    public StaffAuthService(JdbcTemplate jdbc, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.dummyHash = passwords.encode("unusable-password-for-absent-accounts");
    }

    public record LoginResult(String token, Instant expiresAt, String failure) {
        public static LoginResult failed(String reason) { return new LoginResult(null, null, reason); }
    }

    private record StaffRow(UUID id, String login, String hash, boolean enabled) {}

    @Transactional
    public LoginResult login(String identifier, String password) {
        String login = identifier.strip().toLowerCase(Locale.ROOT);
        String loginHash = SecureToken.sha256(login);
        Instant now = Instant.now();

        jdbc.update("INSERT INTO staff_login_throttles(login_hash) VALUES (?) ON CONFLICT DO NOTHING", loginHash);
        var attempts = jdbc.queryForMap(
            "SELECT window_started_at, failed_attempts, locked_until FROM staff_login_throttles WHERE login_hash=? FOR UPDATE",
            loginHash);
        Instant locked = attempts.get("locked_until") == null ? null
            : ((java.sql.Timestamp) attempts.get("locked_until")).toInstant();
        if (locked != null && locked.isAfter(now)) {
            return LoginResult.failed("RATE_LIMITED");
        }
        Instant start = ((java.sql.Timestamp) attempts.get("window_started_at")).toInstant();
        int failed = ((Number) attempts.get("failed_attempts")).intValue();
        if (start.plus(WINDOW).isBefore(now) || (locked != null && !locked.isAfter(now))) {
            failed = 0;
            jdbc.update("UPDATE staff_login_throttles SET window_started_at=?, failed_attempts=0, locked_until=NULL WHERE login_hash=?",
                java.sql.Timestamp.from(now), loginHash);
        }

        List<StaffRow> users = jdbc.query(
            "SELECT id, login, password_hash, enabled FROM staff_users WHERE lower(login)=? FOR UPDATE",
            (rs, row) -> new StaffRow(rs.getObject("id", UUID.class), rs.getString("login"),
                rs.getString("password_hash"), rs.getBoolean("enabled")),
            login);
        StaffRow user = users.isEmpty() ? null : users.getFirst();
        boolean matches = passwords.matches(password, user == null ? dummyHash : user.hash());
        if (user == null || !user.enabled() || !matches) {
            int next = failed + 1;
            Instant until = next >= MAX_FAILURES ? now.plus(WINDOW) : null;
            jdbc.update("UPDATE staff_login_throttles SET failed_attempts=?, locked_until=? WHERE login_hash=?",
                next, until == null ? null : java.sql.Timestamp.from(until), loginHash);
            // Important: return rather than throw; failed-attempt counters must COMMIT.
            return LoginResult.failed(next >= MAX_FAILURES ? "RATE_LIMITED" : "UNAUTHENTICATED");
        }

        String token = SecureToken.generate();
        Instant expires = now.plus(SESSION_TTL);
        jdbc.update("INSERT INTO staff_sessions(token_hash, staff_user_id, expires_at) VALUES (?,?,?)",
            SecureToken.sha256(token), user.id(), java.sql.Timestamp.from(expires));
        jdbc.update("DELETE FROM staff_login_throttles WHERE login_hash=?", loginHash);
        return new LoginResult(token, expires, null);
    }

    @Transactional(readOnly = true)
    public StaffPrincipal authenticate(String token) {
        if (token == null || token.length() != 43) return null;
        String tokenHash = SecureToken.sha256(token);
        List<StaffRow> matches = jdbc.query("""
            SELECT u.id, u.login, u.password_hash, u.enabled FROM staff_sessions s
            JOIN staff_users u ON u.id = s.staff_user_id
            WHERE s.token_hash=? AND s.revoked_at IS NULL AND s.expires_at > now() AND u.enabled=true
            """, (rs, row) -> new StaffRow(rs.getObject("id", UUID.class), rs.getString("login"),
                rs.getString("password_hash"), rs.getBoolean("enabled")), tokenHash);
        if (matches.isEmpty()) return null;
        StaffRow user = matches.getFirst();
        Set<String> roles = jdbc.query("""
            SELECT role_key FROM staff_role_assignments WHERE staff_user_id=?
            """, (rs, row) -> rs.getString("role_key"), user.id()).stream().collect(Collectors.toUnmodifiableSet());
        return new StaffPrincipal(user.id(), user.login(), tokenHash, roles);
    }

    @Transactional
    public void logout(StaffPrincipal principal) {
        jdbc.update("UPDATE staff_sessions SET revoked_at=now() WHERE token_hash=? AND revoked_at IS NULL",
            principal.tokenHash());
    }
}
