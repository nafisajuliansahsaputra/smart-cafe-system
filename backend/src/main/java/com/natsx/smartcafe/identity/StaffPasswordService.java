package com.natsx.smartcafe.identity;

import com.natsx.smartcafe.common.api.ApiFailureException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authenticated self-service credential rotation. The staff row lock serializes
 * password changes against login, which also locks this row before issuing a
 * session. Failed current-password checks commit their own rate-limit counters.
 */
@Service
public class StaffPasswordService {
    private static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;

    public StaffPasswordService(JdbcTemplate jdbc, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.passwords = passwords;
    }

    private Object dbUuid(UUID value) {
        return com.natsx.smartcafe.common.db.JdbcUuid.parameter(
            value,com.natsx.smartcafe.common.db.JdbcSqlDialect.detect(jdbc));
    }

    private Object dbUtc(Instant instant) {
        return com.natsx.smartcafe.common.db.JdbcSqlDialect.detect(jdbc)
            == com.natsx.smartcafe.common.db.JdbcSqlDialect.Vendor.MYSQL
            ? java.time.LocalDateTime.ofInstant(instant,java.time.ZoneOffset.UTC)
            : Timestamp.from(instant);
    }

    private String dbNow() {
        return com.natsx.smartcafe.common.db.JdbcSqlDialect.detect(jdbc)
            == com.natsx.smartcafe.common.db.JdbcSqlDialect.Vendor.MYSQL
            ? "UTC_TIMESTAMP(6)" : "now()";
    }

    public enum Result { CHANGED, INVALID_CURRENT, RATE_LIMITED, REUSED, REVOKED }
    private record Credential(String hash, boolean enabled) {}

    @Transactional
    public Result change(StaffPrincipal actor, String currentPassword,
                         String newPassword, String correlationId) {
        // Match the safe 72-byte BCrypt limit used by staff provisioning.
        if (newPassword == null || newPassword.length() < 16 ||
            newPassword.length() > 72 ||
            newPassword.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new ApiFailureException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "New password must contain 16-72 printable ASCII characters");
        }
        List<Credential> users = jdbc.query("""
            SELECT password_hash, enabled FROM staff_users WHERE id=? FOR UPDATE
            """, (rs, n) -> new Credential(rs.getString("password_hash"),
                rs.getBoolean("enabled")), dbUuid(actor.staffId()));
        if (users.isEmpty() || !users.getFirst().enabled()) return Result.REVOKED;
        String sessionSql="""
            SELECT count(*) FROM staff_sessions
            WHERE staff_user_id=? AND token_hash=? AND revoked_at IS NULL
              AND expires_at >
            """+dbNow();
        Integer sessions = jdbc.queryForObject(
            sessionSql,Integer.class,dbUuid(actor.staffId()),actor.tokenHash());
        if (sessions == null || sessions != 1) return Result.REVOKED;

        UUID staffId = actor.staffId();
        Instant now = Instant.now();
        String ensure=com.natsx.smartcafe.common.db.JdbcSqlDialect.detect(jdbc)
            == com.natsx.smartcafe.common.db.JdbcSqlDialect.Vendor.MYSQL
            ? "INSERT INTO staff_password_change_throttles(staff_user_id) VALUES (?) ON DUPLICATE KEY UPDATE staff_user_id=staff_user_id"
            : "INSERT INTO staff_password_change_throttles(staff_user_id) VALUES (?) ON CONFLICT DO NOTHING";
        jdbc.update(ensure,dbUuid(staffId));
        var attempts = jdbc.queryForMap("""
            SELECT window_started_at, failed_attempts, locked_until
            FROM staff_password_change_throttles WHERE staff_user_id=? FOR UPDATE
            """, dbUuid(staffId));
        Instant locked = attempts.get("locked_until") == null ? null
            : com.natsx.smartcafe.common.db.JdbcUtcTime.toInstant(attempts.get("locked_until"));
        if (locked != null && locked.isAfter(now)) return Result.RATE_LIMITED;
        Instant start = com.natsx.smartcafe.common.db.JdbcUtcTime.toInstant(attempts.get("window_started_at"));
        int failures = ((Number) attempts.get("failed_attempts")).intValue();
        if (start.plus(WINDOW).isBefore(now) || locked != null) {
            failures = 0;
            jdbc.update("""
                UPDATE staff_password_change_throttles
                SET window_started_at=?,failed_attempts=0,locked_until=NULL
                WHERE staff_user_id=?
                """, dbUtc(now), dbUuid(staffId));
        }

        String oldHash = users.getFirst().hash();
        if (!passwords.matches(currentPassword, oldHash)) {
            int next = failures + 1;
            Instant until = next >= MAX_FAILURES ? now.plus(WINDOW) : null;
            jdbc.update("""
                UPDATE staff_password_change_throttles
                SET failed_attempts=?,locked_until=? WHERE staff_user_id=?
                """, next, until == null ? null : dbUtc(until), dbUuid(staffId));
            // Do not throw here: the throttle must commit with the failed check.
            return next >= MAX_FAILURES ? Result.RATE_LIMITED : Result.INVALID_CURRENT;
        }
        if (passwords.matches(newPassword, oldHash)) return Result.REUSED;

        jdbc.update("UPDATE staff_users SET password_hash=? WHERE id=?",
            passwords.encode(newPassword), dbUuid(staffId));
        jdbc.update("UPDATE staff_sessions SET revoked_at="+dbNow()
            +" WHERE staff_user_id=? AND revoked_at IS NULL",dbUuid(staffId));
        jdbc.update("DELETE FROM staff_password_change_throttles WHERE staff_user_id=?",dbUuid(staffId));
        var vendor=com.natsx.smartcafe.common.db.JdbcSqlDialect.detect(jdbc);
        String audit=vendor==com.natsx.smartcafe.common.db.JdbcSqlDialect.Vendor.MYSQL
            ? """
                INSERT INTO audit_logs(actor_type,actor_id,operation,resource_type,
                                       resource_id,correlation_id,safe_before_after_diff)
                VALUES ('STAFF',?,'STAFF_PASSWORD_CHANGED','STAFF_USER',?,?,
                    JSON_OBJECT('allSessionsRevoked',true))
                """
            : """
                INSERT INTO audit_logs(actor_type,actor_id,operation,resource_type,
                                       resource_id,correlation_id,safe_before_after_diff)
                VALUES ('STAFF',?,'STAFF_PASSWORD_CHANGED','STAFF_USER',?,?,
                    jsonb_build_object('allSessionsRevoked',true))
                """;
        jdbc.update(audit,dbUuid(staffId),dbUuid(staffId),correlationId);
        return Result.CHANGED;
    }
}
