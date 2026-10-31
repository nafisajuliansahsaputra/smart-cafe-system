package com.natsx.smartcafe.payments;

import com.natsx.smartcafe.common.api.ApiFailureException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADMIN-only read model for non-payable, unresolved FAKE attempts.
 *
 * A past expires_at is only an OVERDUE warning: it does NOT prove that a
 * provider expired or failed to collect funds. Never mutate attempts,
 * intents, orders, receipts or kitchen tickets from this view.
 */
public final class SandboxUnresolvedAttemptReview {
    private static final int PAGE_SIZE=20;
    private static final Set<String> FILTERS=Set.of(
        "ALL","OVERDUE","SUCCESS_CLAIM","FLAGGED_CLAIM","NO_EVIDENCE");
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SandboxUnresolvedAttemptReview(JdbcTemplate jdbc,Clock clock) {
        this.jdbc=Objects.requireNonNull(jdbc);
        this.clock=Objects.requireNonNull(clock);
    }

    public record Item(UUID id,UUID orderId,String method,long amountIdr,
        String providerReference,String attemptState,String paymentState,
        Instant expiresAt,boolean overdue,long evidenceCount,
        long successClaims,long flaggedClaims,Instant lastEvidenceAt) {}

    public record Page(int page,String filter,Instant checkedAt,boolean hasNext,
                       List<Item> items) {}

    public Page list(int page,String filter) {
        if(page<0||page>1000||filter==null||!FILTERS.contains(filter))
            throw new ApiFailureException(HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR","Invalid pending-attempt filter or page");
        Instant observedAt=clock.instant();
        var rows=jdbc.query("""
            WITH unresolved AS (
              SELECT a.id,p.order_id,a.method,a.amount_idr,
                     a.provider_reference,a.state AS attempt_state,
                     p.aggregate_status AS payment_state,a.expires_at,
                     (a.expires_at<=?) AS overdue,
                     COALESCE(ev.count_all,0) AS evidence_count,
                     COALESCE(ev.count_success,0) AS success_claims,
                     COALESCE(ev.count_flagged,0) AS flagged_claims,
                     ev.last_received
              FROM digital_payment_attempts a
              JOIN payment_intents p ON p.id=a.payment_intent_id
              LEFT JOIN LATERAL (
                SELECT COUNT(*) AS count_all,
                  COUNT(*) FILTER (WHERE e.observed_state='SUCCEEDED')
                    AS count_success,
                  COUNT(*) FILTER (WHERE e.classification<>'QUARANTINED')
                    AS count_flagged,
                  MAX(e.received_at) AS last_received
                FROM sandbox_payment_event_inbox e
                WHERE e.attempt_id=a.id AND e.provider_key='SANDBOX_FAKE'
              ) ev ON TRUE
              WHERE a.provider_key='SANDBOX_FAKE'
                AND a.state IN ('CREATED','PENDING')
                AND p.method_selected IN ('QRIS','VA')
            )
            SELECT * FROM unresolved
            WHERE (?='ALL'
              OR (?='OVERDUE' AND overdue)
              OR (?='SUCCESS_CLAIM' AND success_claims>0)
              OR (?='FLAGGED_CLAIM' AND flagged_claims>0)
              OR (?='NO_EVIDENCE' AND evidence_count=0))
            ORDER BY CASE WHEN flagged_claims>0 THEN 0
                          WHEN success_claims>0 THEN 1
                          WHEN overdue THEN 2 ELSE 3 END,
                     expires_at ASC,id ASC
            LIMIT ? OFFSET ?
            """,(rs,n)->{
                Timestamp evidenceAt=rs.getTimestamp("last_received");
                return new Item(rs.getObject("id",UUID.class),
                    rs.getObject("order_id",UUID.class),rs.getString("method"),
                    rs.getLong("amount_idr"),rs.getString("provider_reference"),
                    rs.getString("attempt_state"),rs.getString("payment_state"),
                    rs.getTimestamp("expires_at").toInstant(),rs.getBoolean("overdue"),
                    rs.getLong("evidence_count"),rs.getLong("success_claims"),
                    rs.getLong("flagged_claims"),
                    evidenceAt==null?null:evidenceAt.toInstant());
            },Timestamp.from(observedAt),filter,filter,filter,filter,filter,
            PAGE_SIZE+1,page*PAGE_SIZE);
        return new Page(page,filter,observedAt,rows.size()>PAGE_SIZE,
            List.copyOf(rows.subList(0,Math.min(PAGE_SIZE,rows.size()))));
    }
}
