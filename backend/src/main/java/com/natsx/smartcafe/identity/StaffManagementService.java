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
    private final org.springframework.security.crypto.password.PasswordEncoder passwords;

    public StaffManagementService(JdbcTemplate jdbc,
        org.springframework.security.crypto.password.PasswordEncoder passwords) {
        this.jdbc=jdbc;
        this.passwords=passwords;
    }

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


    public record CreatedStaff(UUID id,String login,String role,boolean enabled) {}

    /**
     * ADMIN-only creation of ordinary staff, disabled pending separate approval.
     * Do not return the secret or its BCrypt digest to the caller.
     */
    @Transactional
    public CreatedStaff create(StaffPrincipal actor,String requestedLogin,
                               String password,String role,String reason,String correlationId) {
        if(!actor.roles().contains("ADMIN")) {
            throw new ApiFailureException(HttpStatus.FORBIDDEN,"FORBIDDEN",
                "Only an administrator may provision staff accounts");
        }
        if(requestedLogin==null||!requestedLogin.matches("[A-Za-z][A-Za-z0-9._-]{2,39}")) {
            throw bad("Login must contain 3-40 permitted characters");
        }
        String login=requestedLogin.toLowerCase(java.util.Locale.ROOT);
        if(!Set.of("WAITER","KITCHEN","CASHIER").contains(role)) {
            throw bad("Only ordinary staff roles are supported for provisioning");
        }
        // BCrypt silently truncates input beyond 72 bytes. Constrain to printable
        // ASCII until a full enrollment and password-recovery policy is approved.
        if(password==null||password.length()<16||password.length()>72||
           password.chars().anyMatch(c->c<33||c>126)) {
            throw bad("Password must contain 16-72 printable ASCII characters");
        }
        if(reason==null||reason.strip().length()<5||reason.strip().length()>300||
           reason.chars().anyMatch(c->c<32||c==127)) {
            throw bad("Reason must contain 5-300 printable characters");
        }
        Long existing=jdbc.queryForObject(
            "SELECT COUNT(*) FROM staff_users WHERE lower(login)=?",Long.class,login);
        if(existing!=null&&existing>0) {
            throw new ApiFailureException(HttpStatus.CONFLICT,"CONFLICT","Login already exists");
        }
        UUID id=UUID.randomUUID();
        int inserted=jdbc.update("""
            INSERT INTO staff_users(id,login,password_hash,enabled)
            VALUES (?,?,?,false) ON CONFLICT (login) DO NOTHING
            """,id,login,passwords.encode(password));
        if(inserted!=1) {
            throw new ApiFailureException(HttpStatus.CONFLICT,"CONFLICT","Login already exists");
        }
        jdbc.update("""
            INSERT INTO staff_role_assignments(staff_user_id,role_key)
            VALUES (?,?)
            """,id,role);
        jdbc.update("""
            INSERT INTO audit_logs(actor_type,actor_id,operation,resource_type,
                                   resource_id,reason,correlation_id,safe_before_after_diff)
            VALUES ('STAFF',?,'STAFF_PROVISIONED','STAFF_USER',?,?,?,
                jsonb_build_object('enabledAfter',false,'role',?))
            """,actor.staffId(),id,reason.strip(),correlationId,role);
        return new CreatedStaff(id,login,role,false);
    }

    private ApiFailureException bad(String message) {
        return new ApiFailureException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message);
    }
}
