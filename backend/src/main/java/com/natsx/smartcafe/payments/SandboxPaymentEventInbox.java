package com.natsx.smartcafe.payments;

import com.natsx.smartcafe.common.api.ApiFailureException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable, internal-only FAKE observation quarantine for future reconciliation.
 *
 * Deliberately NOT a Spring bean or controller, NO signature verifier and NO
 * network callback surface. Do NOT attach a production webhook to this class.
 * No code herein modifies payment intents, attempts, orders or receipts.
 */
public final class SandboxPaymentEventInbox {
    private static final String FAKE_PROVIDER="SANDBOX_FAKE";
    private static final String FAKE_ENVIRONMENT="SANDBOX_FAKE_NOT_MONEY";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public record Observation(String eventId,String providerReference,
                              PaymentGateway.Method method,PaymentGateway.State state,
                              long reportedAmountIdr,String currency,String environment) {}
    public record Evidence(UUID id,UUID attemptId,String eventId,
                           String classification,String payloadFingerprint) {}
    private record Attempt(UUID id,String method,String state,long amountIdr,
                           String currency,String aggregateState) {}

    public SandboxPaymentEventInbox(JdbcTemplate jdbc,TransactionTemplate transactions) {
        this.jdbc=Objects.requireNonNull(jdbc);
        this.transactions=Objects.requireNonNull(transactions);
    }

    /**
     * Records untrusted claims, never payment truth. The caller is a synthetic
     * test fixture only; an eventual provider adapter needs independent
     * cryptographic verification before anything can become authoritative.
     */
    public Evidence record(Observation incoming) {
        validate(incoming);
        String fingerprint=fingerprint(incoming);
        return transactions.execute(tx->recordLocked(incoming,fingerprint));
    }

    private Evidence recordLocked(Observation input,String fingerprint) {
        // For known attempt references, serialize classification with any
        // changing attempt state. This does NOT grant the right to mutate it.
        var attempts=jdbc.query("""
            SELECT a.id,a.method,a.state,a.amount_idr,a.currency,
                   p.aggregate_status
            FROM digital_payment_attempts a
            JOIN payment_intents p ON p.id=a.payment_intent_id
            WHERE a.provider_key=? AND a.provider_reference=?
            FOR UPDATE OF a
            """,(rs,n)->new Attempt(rs.getObject("id",UUID.class),
                rs.getString("method"),rs.getString("state"),
                rs.getLong("amount_idr"),rs.getString("currency"),
                rs.getString("aggregate_status")),
            FAKE_PROVIDER,input.providerReference());
        Attempt matched=attempts.isEmpty()?null:attempts.getFirst();
        String classification=classify(input,matched);
        UUID attemptId=matched==null?null:matched.id();
        UUID evidenceId=UUID.randomUUID();
        int created=jdbc.update("""
            INSERT INTO sandbox_payment_event_inbox(
                id,provider_key,provider_event_id,provider_reference,
                payload_fingerprint,method,observed_state,
                reported_amount_idr,reported_currency,reported_environment,
                attempt_id,classification)
            VALUES (?,'SANDBOX_FAKE',?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (provider_key,provider_event_id) DO NOTHING
            """,evidenceId,input.eventId(),input.providerReference(),
            fingerprint,input.method().name(),input.state().name(),
            input.reportedAmountIdr(),input.currency(),input.environment(),
            attemptId,classification);
        if(created==1)return new Evidence(evidenceId,attemptId,input.eventId(),
            classification,fingerprint);
        // Duplicate event identity must mean exactly the same canonical fields:
        // a changed amount/reference/status/environment must NEVER be ignored.
        var previous=jdbc.query("""
            SELECT id,attempt_id,provider_event_id,classification,payload_fingerprint
            FROM sandbox_payment_event_inbox
            WHERE provider_key='SANDBOX_FAKE' AND provider_event_id=?
            """,(rs,n)->new Evidence(rs.getObject("id",UUID.class),
                rs.getObject("attempt_id",UUID.class),
                rs.getString("provider_event_id"),
                rs.getString("classification"),
                rs.getString("payload_fingerprint")),input.eventId());
        if(previous.size()!=1||!previous.getFirst().payloadFingerprint().equals(fingerprint)) {
            throw new ApiFailureException(HttpStatus.CONFLICT,"CONFLICT",
                "Provider event identity reused with conflicting contents");
        }
        return previous.getFirst();
    }

    private String classify(Observation incoming,Attempt attempt) {
        if(attempt==null)return "UNKNOWN_REFERENCE";
        if(!FAKE_ENVIRONMENT.equals(incoming.environment()))return "ENVIRONMENT_MISMATCH";
        if(!attempt.method().equals(incoming.method().name())||
           attempt.amountIdr()!=incoming.reportedAmountIdr()||
           !attempt.currency().equals(incoming.currency()))return "FINANCIAL_MISMATCH";
        if("PAID".equals(attempt.aggregateState())||"REFUNDED".equals(attempt.aggregateState()))
            return "ALREADY_SETTLED";
        if(incoming.state()==PaymentGateway.State.SUCCEEDED &&
           "EXPIRED".equals(attempt.state()))return "LATE_SUCCESS";
        if(incoming.state()==PaymentGateway.State.SUCCEEDED &&
           ("FAILED".equals(attempt.state())||"CANCELLED".equals(attempt.state())))
            return "CONTRADICTORY_STATE";
        return "QUARANTINED";
    }

    private void validate(Observation incoming) {
        if(incoming==null||incoming.eventId()==null||
           !incoming.eventId().matches("[A-Za-z0-9_-]{8,128}")||
           incoming.providerReference()==null||
           !incoming.providerReference().matches("FAKE_[a-f0-9]{32}")||
           incoming.method()==null||incoming.state()==null||
           incoming.reportedAmountIdr()<=0||incoming.reportedAmountIdr()>1_000_000_000_000L||
           incoming.currency()==null||!incoming.currency().matches("[A-Z]{3}")||
           incoming.environment()==null||
           !incoming.environment().matches("[A-Z_]{4,60}")) {
            throw new ApiFailureException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",
                "Invalid sandbox observation");
        }
    }

    private String fingerprint(Observation input) {
        // Bounded ASCII values make this unambiguous; do not persist a raw
        // webhook body, signature, account number, customer PII or secret.
        String canonical=String.join("|",input.providerReference(),
            input.method().name(),input.state().name(),
            Long.toString(input.reportedAmountIdr()),input.currency(),input.environment());
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch(NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 provider unavailable",e);
        }
    }
}
