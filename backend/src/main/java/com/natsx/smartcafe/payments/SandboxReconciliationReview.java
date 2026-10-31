package com.natsx.smartcafe.payments;

import com.natsx.smartcafe.common.api.ApiFailureException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Read-only operator view of untrusted synthetic event evidence.
 *
 * This class has NO mutation, provider status authority or payment settlement.
 * Query results may be stale if new events arrive between requests.
 */
public final class SandboxReconciliationReview {
    private static final int SIZE=20;
    private static final Set<String> FILTERS=Set.of(
        "ALL","QUARANTINED","UNKNOWN_REFERENCE","FINANCIAL_MISMATCH",
        "ENVIRONMENT_MISMATCH","LATE_SUCCESS","CONTRADICTORY_STATE","ALREADY_SETTLED");
    private final JdbcTemplate jdbc;

    public SandboxReconciliationReview(JdbcTemplate jdbc) {
        this.jdbc=java.util.Objects.requireNonNull(jdbc);
    }

    public record Evidence(UUID id,String eventId,String reference,
        String classification,String claimedState,String method,
        long reportedAmountIdr,String currency,String environment,
        Instant receivedAt,UUID attemptId,String currentAttemptState,
        String currentPaymentState,String latestTriageAction,
        Instant latestTriageAt,UUID latestTriageActor) {}
    public record Page(int page,String classification,boolean hasNext,
                       List<Evidence> items,long totalEvidence,long successClaims,
                       long flaggedEvidence) {}

    public Page list(int page,String classification) {
        if(page<0||page>1000||classification==null||
           !FILTERS.contains(classification)) {
            throw new ApiFailureException(HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR","Invalid reconciliation filter or page");
        }
        var totals=jdbc.queryForObject("""
            SELECT COUNT(*) AS total,
              COUNT(*) FILTER (WHERE observed_state='SUCCEEDED') AS claimed,
              COUNT(*) FILTER (WHERE classification<>'QUARANTINED') AS flagged
            FROM sandbox_payment_event_inbox
            WHERE provider_key='SANDBOX_FAKE'
            """,(rs,n)->new long[]{
                rs.getLong("total"),rs.getLong("claimed"),rs.getLong("flagged")
            });
        var rows=jdbc.query("""
            SELECT e.id,e.provider_event_id,e.provider_reference,
                   e.classification,e.observed_state,e.method,
                   e.reported_amount_idr,e.reported_currency,
                   e.reported_environment,e.received_at,e.attempt_id,
                   a.state AS attempt_state,p.aggregate_status AS payment_state,
                   t.action_code AS triage_action,
                   t.created_at AS triage_at,t.actor_staff_id AS triage_actor
            FROM sandbox_payment_event_inbox e
            LEFT JOIN digital_payment_attempts a ON a.id=e.attempt_id
              AND a.provider_key='SANDBOX_FAKE'
            LEFT JOIN payment_intents p ON p.id=a.payment_intent_id
            LEFT JOIN LATERAL (
                SELECT action_code,created_at,actor_staff_id
                FROM sandbox_payment_triage_actions t
                WHERE t.evidence_id=e.id
                ORDER BY created_at DESC,id DESC LIMIT 1
            ) t ON true
            WHERE e.provider_key='SANDBOX_FAKE'
              AND (?='ALL' OR e.classification=?)
            ORDER BY e.received_at DESC,e.id DESC
            LIMIT ? OFFSET ?
            """,(rs,n)->new Evidence(
                rs.getObject("id",UUID.class),rs.getString("provider_event_id"),
                rs.getString("provider_reference"),rs.getString("classification"),
                rs.getString("observed_state"),rs.getString("method"),
                rs.getLong("reported_amount_idr"),
                rs.getString("reported_currency"),
                rs.getString("reported_environment"),
                rs.getTimestamp("received_at").toInstant(),
                rs.getObject("attempt_id",UUID.class),rs.getString("attempt_state"),
                rs.getString("payment_state"),rs.getString("triage_action"),
                rs.getTimestamp("triage_at")==null?null:
                    rs.getTimestamp("triage_at").toInstant(),
                rs.getObject("triage_actor",UUID.class)),
            classification,classification,SIZE+1,page*SIZE);
        return new Page(page,classification,rows.size()>SIZE,
            List.copyOf(rows.subList(0,Math.min(rows.size(),SIZE))),
            totals[0],totals[1],totals[2]);
    }
}
