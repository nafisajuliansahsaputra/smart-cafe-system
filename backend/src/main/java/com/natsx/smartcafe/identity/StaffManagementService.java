package com.natsx.smartcafe.identity;

import com.natsx.smartcafe.common.api.ApiFailureException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Initial staff lifecycle: list and suspend/reactivate ordinary staff only.
 * Privileged users are intentionally immutable in this UI until full owner
 * recovery and role-assignment policies have been approved.
 */
@Service
public class StaffManagementService {
    private final JdbcTemplate jdbc;

    public StaffManagementService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public record Entry(UUID id,String login,boolean enabled,List<String> roles,Instant createdAt) {}
    public record StaffPage(int page,boolean hasNext,List<Entry> items) {}
    private record Current(boolean enabled,String login) {}

    @Transactional(readOnly=true)
    public StaffPage list(int page) {
        if(page<0||page>2000)throw bad("Invalid staff page");
        var rows=jdbc.query("""
            SELECT u.id,u.login,u.enabled,u.created_at,
                   COALESCE(string_agg(a.role_key,',' ORDER BY a.role_key),'') AS role_keys
            FROM staff_users u
            LEFT JOIN staff_role_assignments a ON a.staff_user_id=u.id
            GROUP BY u.id
            ORDER BY lower(u.login),u.id
            LIMIT 26 OFFSET ?
            """,(rs,n)->new Entry(
                rs.getObject("id",UUID.class),rs.getString("login"),
                rs.getBoolean("enabled"),
                rs.getString("role_keys").isEmpty()?List.of():
                    Arrays.asList(rs.getString("role_keys").split(",")),
                rs.getTimestamp("created_at").toInstant()),page*25);
        return new StaffPage(page,rows.size()>25,rows.stream().limit(25).toList());
    }

    @Transactional
    public void change(UUID targetId,StaffPrincipal actor,boolean enabled,
                       boolean expectedEnabled,String reason,String correlationId) {
        if(reason==null||reason.strip().length()<5||reason.strip().length()>300||
            reason.chars().anyMatch(c->c<32||c==127)) {
            throw bad("Reason must contain 5-300 printable characters");
        }
        if(targetId.equals(actor.staffId())) {
            throw new ApiFailureException(HttpStatus.FORBIDDEN,"FORBIDDEN",
                "Cannot change own account status");
        }
        var rows=jdbc.query("SELECT enabled,login FROM staff_users WHERE id=? FOR UPDATE",
            (rs,n)->new Current(rs.getBoolean("enabled"),rs.getString("login")),targetId);
        if(rows.isEmpty())throw new ApiFailureException(HttpStatus.NOT_FOUND,"NOT_FOUND","Staff not found");
        var current=rows.getFirst();
        var roles=Set.copyOf(jdbc.query(
            "SELECT role_key FROM staff_role_assignments WHERE staff_user_id=?",
            (rs,n)->rs.getString("role_key"),targetId));
        if(roles.contains("ADMIN")||roles.contains("MANAGER")) {
            throw new ApiFailureException(HttpStatus.FORBIDDEN,"FORBIDDEN",
                "Privileged staff status requires a separately approved recovery procedure");
        }
        if(current.enabled()!=expectedEnabled || current.enabled()==enabled) {
            throw new ApiFailureException(HttpStatus.CONFLICT,"CONFLICT",
                "Staff status changed; refresh the account before retrying");
        }
        jdbc.update("UPDATE staff_users SET enabled=? WHERE id=?",enabled,targetId);
        if(!enabled) {
            jdbc.update("""
                UPDATE staff_sessions SET revoked_at=now()
                WHERE staff_user_id=? AND revoked_at IS NULL
                """,targetId);
        }
        jdbc.update("""
            INSERT INTO audit_logs(actor_type,actor_id,operation,resource_type,
                                   resource_id,reason,correlation_id,safe_before_after_diff)
            VALUES ('STAFF',?,?,'STAFF_USER',?,?,?,
                jsonb_build_object('enabledBefore',?,'enabledAfter',?))
            """,actor.staffId(),enabled?"STAFF_ENABLED":"STAFF_DISABLED",
                targetId,reason.strip(),correlationId,current.enabled(),enabled);
    }

    private ApiFailureException bad(String message) {
        return new ApiFailureException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message);
    }
}
