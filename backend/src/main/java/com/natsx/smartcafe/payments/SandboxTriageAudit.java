package com.natsx.smartcafe.payments;

import com.natsx.smartcafe.common.api.ApiFailureException;
import com.natsx.smartcafe.identity.StaffPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sandbox fake-event investigation log, NEVER a payment verification or
 * settlement service. Fixed action codes prevent free-text PII and avoid
 * representing any fake event as approved or resolved.
 */
public final class SandboxTriageAudit {
    private static final Set<String> ACTIONS=
        Set.of("INVESTIGATE","REQUEST_PROVIDER_REVIEW","ESCALATE");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public record Action(UUID id,UUID evidenceId,UUID actorStaffId,
                         String code,Instant createdAt) {}

    public SandboxTriageAudit(JdbcTemplate jdbc,TransactionTemplate transactions) {
        this.jdbc=java.util.Objects.requireNonNull(jdbc);
        this.transactions=java.util.Objects.requireNonNull(transactions);
    }

    public Action append(StaffPrincipal actor,UUID evidenceId,String code,String key) {
        if(actor==null||actor.staffId()==null||
           actor.roles()==null||!actor.roles().contains("ADMIN")) {
            throw new ApiFailureException(HttpStatus.FORBIDDEN,"FORBIDDEN",
                "Administrator investigation required");
        }
        if(evidenceId==null||code==null||!ACTIONS.contains(code)||
           key==null||!key.matches("[A-Za-z0-9_-]{8,128}")) {
            throw new ApiFailureException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",
                "Invalid sandbox investigation action");
        }
        return transactions.execute(tx->appendLocked(actor,evidenceId,code,key));
    }

    private Action appendLocked(StaffPrincipal actor,UUID evidenceId,String code,String key) {
        // Lock the immutable evidence row to serialize all competing
        // same-key actions, even when several application replicas run.
        var evidence=jdbc.query("""
            SELECT id FROM sandbox_payment_event_inbox
            WHERE id=? AND provider_key='SANDBOX_FAKE'
            FOR UPDATE
            """,(rs,n)->rs.getObject("id",UUID.class),evidenceId);
        if(evidence.isEmpty())throw new ApiFailureException(HttpStatus.NOT_FOUND,
            "NOT_FOUND","Sandbox evidence not found");

        List<Action> previous=jdbc.query("""
            SELECT id,evidence_id,actor_staff_id,action_code,created_at
            FROM sandbox_payment_triage_actions
            WHERE evidence_id=? AND idempotency_key=?
            """,(rs,n)->fromRow(rs),evidenceId,key);
        if(!previous.isEmpty()) {
            Action old=previous.getFirst();
            if(!old.code().equals(code) || !old.actorStaffId().equals(actor.staffId()))
                throw new ApiFailureException(HttpStatus.CONFLICT,"CONFLICT",
                    "Investigation key already used for another action");
            return old;
        }
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO sandbox_payment_triage_actions(
                id,evidence_id,actor_staff_id,action_code,idempotency_key)
            VALUES (?,?,?,?,?)
            """,id,evidenceId,actor.staffId(),code,key);
        return jdbc.queryForObject("""
            SELECT id,evidence_id,actor_staff_id,action_code,created_at
            FROM sandbox_payment_triage_actions WHERE id=?
            """,(rs,n)->fromRow(rs),id);
    }

    private Action fromRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Action(rs.getObject("id",UUID.class),
            rs.getObject("evidence_id",UUID.class),
            rs.getObject("actor_staff_id",UUID.class),
            rs.getString("action_code"),
            rs.getTimestamp("created_at").toInstant());
    }
}
