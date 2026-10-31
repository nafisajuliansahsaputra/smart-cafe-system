package com.natsx.smartcafe.ops;

import com.natsx.smartcafe.common.api.ApiFailureException;
import com.natsx.smartcafe.identity.StaffPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manager-only review trail. Non-zero variance remains immutable and never
 * equals proof of loss, fraud, theft or recovered bank settlement.
 */
@Service
public class CashVarianceReviewService {
    private static final Set<String> ACTIONS = Set.of(
        "REQUEST_RECOUNT","DOCUMENT_EVIDENCE","ESCALATE");
    private final JdbcTemplate jdbc;
    public CashVarianceReviewService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Case(UUID shiftId, Instant closedAt, long expectedDrawerIdr,
                       long countedCashIdr, long differenceIdr, String latestAction,
                       Instant latestActionAt, UUID latestActorId) {}
    public record CasePage(int page, boolean hasNext, List<Case> items) {}
    public record ReviewAction(UUID id, UUID shiftId, UUID actorId, String actionCode,
                               String note, Instant createdAt) {}
    private record StoredAction(ReviewAction action, String key) {}

    @Transactional(readOnly = true)
    public CasePage cases(StaffPrincipal actor, int page) {
        requireManager(actor);
        if (page < 0 || page > 1000) throw bad("Invalid review page");
        var rows = jdbc.query("""
            SELECT s.id,s.closed_at,s.expected_drawer_idr,s.counted_cash_idr,
                   s.difference_idr,a.action_code,a.created_at AS action_at,
                   a.actor_staff_id
            FROM cash_drawer_shifts s
            LEFT JOIN LATERAL (
              SELECT action_code,created_at,actor_staff_id
              FROM cash_variance_review_actions
              WHERE shift_id=s.id
              ORDER BY created_at DESC,id DESC LIMIT 1
            ) a ON true
            WHERE s.closed_at IS NOT NULL AND s.difference_idr<>0
            ORDER BY s.closed_at DESC,s.id DESC
            LIMIT 21 OFFSET ?
            """,(rs,n)->new Case(
                rs.getObject("id",UUID.class),
                rs.getTimestamp("closed_at").toInstant(),
                rs.getLong("expected_drawer_idr"),rs.getLong("counted_cash_idr"),
                rs.getLong("difference_idr"),rs.getString("action_code"),
                rs.getTimestamp("action_at")==null?null:
                    rs.getTimestamp("action_at").toInstant(),
                rs.getObject("actor_staff_id",UUID.class)),page*20);
        return new CasePage(page,rows.size()>20,
            rows.stream().limit(20).toList());
    }

    @Transactional(readOnly = true)
    public List<ReviewAction> history(StaffPrincipal actor, UUID shiftId) {
        requireManager(actor);
        requireVariance(shiftId,false);
        return jdbc.query("""
            SELECT id,shift_id,actor_staff_id,action_code,note,created_at
            FROM cash_variance_review_actions
            WHERE shift_id=? ORDER BY created_at DESC,id DESC LIMIT 100
            """,(rs,n)->new ReviewAction(
                rs.getObject("id",UUID.class),rs.getObject("shift_id",UUID.class),
                rs.getObject("actor_staff_id",UUID.class),
                rs.getString("action_code"),rs.getString("note"),
                rs.getTimestamp("created_at").toInstant()),shiftId);
    }

    @Transactional
    public ReviewAction add(StaffPrincipal actor, UUID shiftId, String code,
                            String note, String key, String requestId) {
        requireManager(actor);
        if (!ACTIONS.contains(code)) throw bad("Unsupported investigation action");
        if (note == null || note.strip().length()<10 || note.strip().length()>300 ||
            note.chars().anyMatch(c->c<32 || c==127)) {
            throw bad("Investigation note must contain 10-300 printable characters");
        }
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,128}")) {
            throw bad("A stable idempotency key is required");
        }
        String clean = note.strip();
        // Lock the CLOSED shift row to serialize all investigation appends to
        // one case across replicas; exact retries never create duplicate audits.
        requireVariance(shiftId,true);
        var prior=jdbc.query("""
            SELECT id,shift_id,actor_staff_id,action_code,note,
                   created_at,idempotency_key
            FROM cash_variance_review_actions
            WHERE shift_id=? AND idempotency_key=?
            """,(rs,n)->new StoredAction(new ReviewAction(
                rs.getObject("id",UUID.class),rs.getObject("shift_id",UUID.class),
                rs.getObject("actor_staff_id",UUID.class),
                rs.getString("action_code"),rs.getString("note"),
                rs.getTimestamp("created_at").toInstant()),
                rs.getString("idempotency_key")),shiftId,key);
        if (!prior.isEmpty()) {
            ReviewAction existing=prior.getFirst().action();
            if (existing.actorId().equals(actor.staffId()) &&
                existing.actionCode().equals(code) && existing.note().equals(clean)) {
                return existing;
            }
            throw conflict("This idempotency key was used for another review");
        }
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO cash_variance_review_actions
                (id,shift_id,actor_staff_id,action_code,note,idempotency_key)
            VALUES (?,?,?,?,?,?)
            """,id,shiftId,actor.staffId(),code,clean,key);
        jdbc.update("""
            INSERT INTO audit_logs(actor_type,actor_id,operation,resource_type,
                resource_id,reason,correlation_id,safe_before_after_diff)
            VALUES ('STAFF',?,'CASH_VARIANCE_REVIEW','CASH_DRAWER_SHIFT',?,?,?,
                jsonb_build_object('reviewAction',CAST(? AS text)))
            """,actor.staffId(),shiftId,clean,requestId,code);
        return jdbc.queryForObject("""
            SELECT id,shift_id,actor_staff_id,action_code,note,created_at
            FROM cash_variance_review_actions WHERE id=?
            """,(rs,n)->new ReviewAction(
                rs.getObject("id",UUID.class),rs.getObject("shift_id",UUID.class),
                rs.getObject("actor_staff_id",UUID.class),
                rs.getString("action_code"),rs.getString("note"),
                rs.getTimestamp("created_at").toInstant()),id);
    }

    private void requireVariance(UUID shiftId,boolean lock) {
        var rows=jdbc.query("""
            SELECT closed_at,difference_idr FROM cash_drawer_shifts WHERE id=?
            """+(lock?" FOR UPDATE":""),(rs,n)->new boolean[]{
                rs.getTimestamp("closed_at")!=null,
                rs.getLong("difference_idr")!=0},shiftId);
        if (rows.isEmpty()) throw new ApiFailureException(
            HttpStatus.NOT_FOUND,"NOT_FOUND","Cash shift is not available");
        if (!rows.getFirst()[0] || !rows.getFirst()[1]) {
            throw conflict("Only closed shifts with nonzero differences require review");
        }
    }
    private void requireManager(StaffPrincipal actor) {
        if (actor==null || actor.roles().stream().noneMatch(
            r->r.equals("MANAGER") || r.equals("ADMIN"))) {
            throw new ApiFailureException(HttpStatus.FORBIDDEN,"FORBIDDEN",
                "Manager or administrator authorization required");
        }
    }
    private ApiFailureException bad(String message) {
        return new ApiFailureException(HttpStatus.BAD_REQUEST,
            "VALIDATION_ERROR",message);
    }
    private ApiFailureException conflict(String message) {
        return new ApiFailureException(HttpStatus.CONFLICT,"CONFLICT",message);
    }
}
