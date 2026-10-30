package com.natsx.smartcafe.identity;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authenticated staff credential rotation with all-session revocation.
 * Bad old-password attempts commit to a shared account-scoped throttle.
 */
@Service
public class StaffPasswordChangeService {
    private static final int MAX_FAILURES=5;
    private static final Duration WINDOW=Duration.ofMinutes(15);
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public StaffPasswordChangeService(JdbcTemplate jdbc,PasswordEncoder encoder) {
        this.jdbc=jdbc;
        this.encoder=encoder;
    }

    public enum Result { CHANGED, INVALID_CREDENTIALS, RATE_LIMITED, SAME_PASSWORD }

    @Transactional
    public Result change(StaffPrincipal principal,String currentPassword,
                         String newPassword,String correlationId) {
        jdbc.update("""
            INSERT INTO staff_password_change_throttles(staff_user_id)
            VALUES (?) ON CONFLICT DO NOTHING
            """,principal.staffId());
        Map<String,Object> throttle=jdbc.queryForMap("""
            SELECT window_started_at,failed_attempts,locked_until
            FROM staff_password_change_throttles WHERE staff_user_id=? FOR UPDATE
            """,principal.staffId());
        Instant now=Instant.now();
        Instant start=((Timestamp)throttle.get("window_started_at")).toInstant();
        Timestamp untilValue=(Timestamp)throttle.get("locked_until");
        Instant locked=untilValue==null?null:untilValue.toInstant();
        if(locked!=null&&locked.isAfter(now)) return Result.RATE_LIMITED;
        int failed=((Number)throttle.get("failed_attempts")).intValue();
        if(start.plus(WINDOW).isBefore(now)||(locked!=null&&!locked.isAfter(now))) {
            failed=0;
            jdbc.update("""
                UPDATE staff_password_change_throttles
                SET window_started_at=?,failed_attempts=0,locked_until=NULL
                WHERE staff_user_id=?
                """,Timestamp.from(now),principal.staffId());
        }
        var users=jdbc.query("""
            SELECT password_hash,enabled FROM staff_users WHERE id=? FOR UPDATE
            """,(rs,n)->new Account(rs.getString("password_hash"),rs.getBoolean("enabled")),
            principal.staffId());
        if(users.isEmpty()||!users.getFirst().enabled()||
            !encoder.matches(currentPassword,users.getFirst().hash())) {
            int next=failed+1;
            jdbc.update("""
                UPDATE staff_password_change_throttles
                SET failed_attempts=?,locked_until=? WHERE staff_user_id=?
                """,next,next>=MAX_FAILURES?Timestamp.from(now.plus(WINDOW)):null,
                principal.staffId());
            return next>=MAX_FAILURES?Result.RATE_LIMITED:Result.INVALID_CREDENTIALS;
        }
        if(encoder.matches(newPassword,users.getFirst().hash())) return Result.SAME_PASSWORD;
        jdbc.update("UPDATE staff_users SET password_hash=? WHERE id=?",
            encoder.encode(newPassword),principal.staffId());
        jdbc.update("""
            UPDATE staff_sessions SET revoked_at=now()
            WHERE staff_user_id=? AND revoked_at IS NULL
            """,principal.staffId());
        jdbc.update("DELETE FROM staff_password_change_throttles WHERE staff_user_id=?",
            principal.staffId());
        jdbc.update("""
            INSERT INTO audit_logs(actor_type,actor_id,operation,resource_type,resource_id,
                reason,correlation_id,safe_before_after_diff)
            VALUES ('STAFF',?,'STAFF_PASSWORD_CHANGED','STAFF_USER',?,
                'Staff-initiated credential rotation',?,
                jsonb_build_object('allSessionsRevoked',true))
            """,principal.staffId(),principal.staffId(),correlationId);
        return Result.CHANGED;
    }

    private record Account(String hash,boolean enabled) {}
}
