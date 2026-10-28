package com.natsx.smartcafe.tables;

import com.natsx.smartcafe.common.api.ApiFailureException;
import com.natsx.smartcafe.audit.AuditTrail;
import com.natsx.smartcafe.identity.SecureToken;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TableVisitService {
    private static final Duration PROOF_TTL = Duration.ofMinutes(10);
    private static final Duration GUEST_TTL = Duration.ofHours(8);
    private final JdbcTemplate jdbc;
    private final AuditTrail audit;

    public TableVisitService(JdbcTemplate jdbc, AuditTrail audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record TableEntry(String tableCode, boolean activeVisit) {}
    public record VisitOpened(UUID id, UUID tableId, String joinProof, Instant proofExpiresAt) {}
    public record JoinResult(UUID guestId, UUID visitId, String accessToken, Instant expiresAt) {}
    public record GuestVisit(UUID guestId, UUID visitId, String tableCode, String displayName) {}
    private record VisitRow(UUID id, String status, String proofHash, Instant proofExpiresAt) {}

    @Transactional(readOnly = true)
    public TableEntry entry(String alias) {
        return jdbc.query("""
            SELECT t.table_code, EXISTS(
                SELECT 1 FROM table_visits v WHERE v.table_id=t.id AND v.status='ACTIVE'
            ) AS active FROM cafe_tables t WHERE t.public_alias=?
            """, rs -> {
            if (!rs.next()) throw missing();
            return new TableEntry(rs.getString("table_code"), rs.getBoolean("active"));
        }, alias);
    }

    @Transactional
    public VisitOpened open(UUID tableId, UUID staffId, String requestId) {
        String status = jdbc.query("SELECT status FROM cafe_tables WHERE id=? FOR UPDATE", rs ->
            rs.next() ? rs.getString("status") : null, tableId);
        if (status == null) throw missing();
        if (!status.equals("AVAILABLE")) throw conflict("Table is unavailable");
        Integer active = jdbc.queryForObject("""
            SELECT count(*) FROM table_visits WHERE table_id=? AND status IN ('ACTIVE','CLOSING')
            """, Integer.class, tableId);
        if (active != null && active > 0) throw conflict("Table already has an active visit");
        String proof = SecureToken.generate();
        Instant expires = Instant.now().plus(PROOF_TTL);
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO table_visits(id,table_id,status,opened_by,join_secret_hash,join_proof_expires_at)
            VALUES (?,?,'ACTIVE',?,?,?)
            """, id, tableId, staffId, SecureToken.sha256(proof), Timestamp.from(expires));
        audit.staff(staffId, "TABLE_VISIT_OPENED", "TABLE_VISIT", id, requestId);
        return new VisitOpened(id, tableId, proof, expires);
    }

    @Transactional
    public VisitOpened rotate(UUID visitId, UUID staffId, String requestId) {
        VisitRow visit = jdbc.query("""
            SELECT id,status,join_secret_hash,join_proof_expires_at
            FROM table_visits WHERE id=? FOR UPDATE
            """, rs -> rs.next() ? readVisit(rs) : null, visitId);
        if (visit == null) throw missing();
        requireWaiterOwnershipOrSupervisor(visitId,staffId);
        if (!visit.status().equals("ACTIVE")) throw conflict("Visit is not active");
        String proof = SecureToken.generate();
        Instant expires = Instant.now().plus(PROOF_TTL);
        jdbc.update("""
            UPDATE table_visits SET join_secret_hash=?,join_proof_expires_at=?,
                rotation_version=rotation_version+1,version=version+1 WHERE id=?
            """, SecureToken.sha256(proof), Timestamp.from(expires), visitId);
        UUID tableId = jdbc.queryForObject("SELECT table_id FROM table_visits WHERE id=?", UUID.class, visitId);
        audit.staff(staffId, "TABLE_JOIN_PROOF_ROTATED", "TABLE_VISIT", visitId, requestId);
        return new VisitOpened(visitId, tableId, proof, expires);
    }

    @Transactional
    public JoinResult join(String alias, String joinProof, String displayName) {
        if (joinProof == null || joinProof.length() != 43) throw missing();
        VisitRow visit = jdbc.query("""
            SELECT v.id,v.status,v.join_secret_hash,v.join_proof_expires_at
            FROM cafe_tables t JOIN table_visits v ON v.table_id=t.id
            WHERE t.public_alias=? AND v.status='ACTIVE'
            FOR SHARE OF v
            """, rs -> rs.next() ? readVisit(rs) : null, alias);
        if (visit == null || visit.proofExpiresAt().isBefore(Instant.now())) throw missing();
        byte[] supplied = SecureToken.sha256(joinProof).getBytes(StandardCharsets.US_ASCII);
        byte[] stored = visit.proofHash().getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(supplied, stored)) throw missing();

        UUID guestId = UUID.randomUUID();
        String token = SecureToken.generate();
        Instant expires = Instant.now().plus(GUEST_TTL);
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guestId, visit.id(), displayName.strip());
        jdbc.update("INSERT INTO guest_sessions(token_hash,guest_id,visit_id,expires_at) VALUES (?,?,?,?)",
            SecureToken.sha256(token), guestId, visit.id(), Timestamp.from(expires));
        return new JoinResult(guestId, visit.id(), token, expires);
    }

    @Transactional(readOnly = true)
    public GuestVisit currentVisit(UUID guestId, UUID visitId) {
        return jdbc.query("""
            SELECT g.display_name,t.table_code FROM visit_guests g
            JOIN table_visits v ON v.id=g.visit_id
            JOIN cafe_tables t ON t.id=v.table_id
            WHERE g.id=? AND g.visit_id=? AND v.status='ACTIVE'
            """, rs -> {
                if (!rs.next()) throw missing();
                return new GuestVisit(guestId, visitId, rs.getString("table_code"), rs.getString("display_name"));
            }, guestId, visitId);
    }

    @Transactional
    public void close(UUID visitId, UUID staffId, String requestId) {
        VisitRow visit = jdbc.query("""
            SELECT id,status,join_secret_hash,join_proof_expires_at FROM table_visits
            WHERE id=? FOR UPDATE
            """, rs -> rs.next() ? readVisit(rs) : null, visitId);
        if (visit == null) throw missing();
        requireWaiterOwnershipOrSupervisor(visitId,staffId);
        if (!visit.status().equals("ACTIVE")) throw conflict("Visit cannot close from current state");
        Integer unresolved = jdbc.queryForObject("""
            SELECT count(*) FROM orders o LEFT JOIN payment_intents p ON p.order_id=o.id
            WHERE o.visit_id=? AND (
                p.id IS NULL
                OR (o.status NOT IN ('REJECTED','CANCELLED') AND
                    (p.aggregate_status NOT IN ('PAID','REFUNDED') OR o.status <> 'SERVED'))
                OR (o.status IN ('REJECTED','CANCELLED') AND
                    p.aggregate_status NOT IN ('UNPAID','AWAITING_CASH','REFUNDED'))
            )
            """, Integer.class, visitId);
        if (unresolved != null && unresolved > 0) throw conflict("Visit has unresolved orders or payments");
        // M4 game-loan constraint must be added before enabling loans.
        jdbc.update("UPDATE table_visits SET status='CLOSED',closed_at=now(),version=version+1 WHERE id=?", visitId);
        jdbc.update("UPDATE guest_sessions SET revoked_at=now() WHERE visit_id=? AND revoked_at IS NULL", visitId);
        audit.staff(staffId, "TABLE_VISIT_CLOSED", "TABLE_VISIT", visitId, requestId);
    }

    private void requireWaiterOwnershipOrSupervisor(UUID visitId,UUID staffId) {
        Boolean supervisor=jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM staff_role_assignments
                          WHERE staff_user_id=? AND role_key IN ('CASHIER','MANAGER','ADMIN'))
            """,Boolean.class,staffId);
        if(Boolean.TRUE.equals(supervisor))return;
        Boolean opener=jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM table_visits WHERE id=? AND opened_by=?)
            """,Boolean.class,visitId,staffId);
        if(!Boolean.TRUE.equals(opener)) {
            throw new ApiFailureException(HttpStatus.FORBIDDEN,"FORBIDDEN",
                "Waiter is not responsible for this visit");
        }
    }

    private VisitRow readVisit(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new VisitRow(rs.getObject("id", UUID.class), rs.getString("status"),
            rs.getString("join_secret_hash"), rs.getTimestamp("join_proof_expires_at").toInstant());
    }

    private ApiFailureException missing() {
        return new ApiFailureException(HttpStatus.NOT_FOUND,"NOT_FOUND","Table visit is not available");
    }

    private ApiFailureException conflict(String message) {
        return new ApiFailureException(HttpStatus.CONFLICT,"CONFLICT",message);
    }
}
