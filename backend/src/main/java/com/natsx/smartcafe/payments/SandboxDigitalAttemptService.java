package com.natsx.smartcafe.payments;

import com.natsx.smartcafe.common.api.ApiFailureException;
import com.natsx.smartcafe.identity.GuestPrincipal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicitly instantiated sandbox-only digital attempt coordinator.
 *
 * Not registered as a Spring bean; there is NO HTTP route, checkout change,
 * provider webhook, or production configuration. This is an internal domain
 * integration step, to be connected only after digital checkout is reviewed.
 * A fake gateway is necessarily in-process: never substitute a network client
 * inside the database row lock or treat a fake result as received money.
 */
public final class SandboxDigitalAttemptService {
    private static final String PROVIDER="SANDBOX_FAKE";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SandboxFakePaymentGateway gateway;
    private final Clock clock;

    public record Attempt(UUID id,UUID orderId,String method,long amountIdr,
                          String providerReference,String state,Instant expiresAt,
                          String nonPayableDisplayHint,String environment) {}
    private record Intent(UUID id,String method,String paymentState,
                          long due,long paid,long orderTotal,String currency,
                          String orderState,String visitState) {}
    private record Stored(UUID id,String method,long amount,String reference,
                          String state,Instant expiresAt) {}

    public SandboxDigitalAttemptService(JdbcTemplate jdbc,
          TransactionTemplate transactions,SandboxFakePaymentGateway gateway,Clock clock) {
        this.jdbc=java.util.Objects.requireNonNull(jdbc);
        this.transactions=java.util.Objects.requireNonNull(transactions);
        this.gateway=java.util.Objects.requireNonNull(gateway);
        this.clock=java.util.Objects.requireNonNull(clock);
    }

    public Attempt create(GuestPrincipal guest,UUID orderId,
                          PaymentGateway.Method method,String key) {
        if(guest==null||guest.guestId()==null||guest.visitId()==null||orderId==null) {
            throw bad("Invalid guest or order");
        }
        if(method==null||key==null||!key.matches("[A-Za-z0-9_-]{8,128}")) {
            throw bad("Invalid sandbox payment method or idempotency key");
        }
        return transactions.execute(status->createLocked(guest,orderId,method,key));
    }

    private Attempt createLocked(GuestPrincipal guest,UUID orderId,
                                  PaymentGateway.Method method,String key) {
        // An order is looked up exclusively inside the authenticated guest's
        // visit. An unknown or foreign order has the same 404 behavior.
        var intents=jdbc.query("""
            SELECT p.id,p.method_selected,p.aggregate_status,p.amount_due_idr,
                   p.amount_paid_idr,p.currency,o.total_idr,o.status AS order_state,
                   v.status AS visit_state
            FROM payment_intents p
            JOIN orders o ON o.id=p.order_id
            JOIN table_visits v ON v.id=o.visit_id
            WHERE o.id=? AND o.guest_id=? AND o.visit_id=?
            FOR UPDATE OF p
            """,(rs,n)->new Intent(rs.getObject("id",UUID.class),
                rs.getString("method_selected"),rs.getString("aggregate_status"),
                rs.getLong("amount_due_idr"),rs.getLong("amount_paid_idr"),
                rs.getLong("total_idr"),rs.getString("currency"),
                rs.getString("order_state"),rs.getString("visit_state")),
            orderId,guest.guestId(),guest.visitId());
        if(intents.isEmpty()) {
            throw new ApiFailureException(HttpStatus.NOT_FOUND,"NOT_FOUND",
                "Order not found");
        }
        var intent=intents.getFirst();
        if(!"ACTIVE".equals(intent.visitState())||
           !"SUBMITTED".equals(intent.orderState())||
           !"PENDING".equals(intent.paymentState())||
           !intent.method().equals(method.name())||
           intent.paid()!=0||intent.due()<=0||intent.due()!=intent.orderTotal()||
           !"IDR".equals(intent.currency())) {
            throw conflict("Order is not eligible for a new digital attempt");
        }

        var previous=jdbc.query("""
            SELECT id,method,amount_idr,provider_reference,state,expires_at
            FROM digital_payment_attempts
            WHERE payment_intent_id=? AND idempotency_key=?
            """,(rs,n)->new Stored(rs.getObject("id",UUID.class),
                rs.getString("method"),rs.getLong("amount_idr"),
                rs.getString("provider_reference"),rs.getString("state"),
                rs.getTimestamp("expires_at").toInstant()),
            intent.id(),key);
        if(!previous.isEmpty()) {
            var old=previous.getFirst();
            if(!old.method().equals(method.name())||old.amount()!=intent.due()) {
                throw conflict("Idempotency key conflicts with stored attempt");
            }
            return response(old,orderId);
        }

        Integer active=jdbc.queryForObject("""
            SELECT count(*) FROM digital_payment_attempts
            WHERE payment_intent_id=? AND state IN ('CREATED','PENDING')
            """,Integer.class,intent.id());
        if(active!=null&&active!=0) {
            // The provider might settle after nominal expiry; do not
            // auto-expire/unlock an unresolved attempt based on wall clock.
            throw conflict("An unresolved digital attempt must be reconciled first");
        }

        // This gateway cannot perform network I/O or create payable QR/VA.
        var created=gateway.create(new PaymentGateway.CreateRequest(
            orderId,intent.due(),method,key));
        // PostgreSQL timestamptz persists microseconds, not nanoseconds.
        // Normalize the first response to the exact database precision so
        // an idempotent replay has byte-for-byte identical expiry semantics.
        Instant persistedExpiry=created.expiresAt().truncatedTo(
            java.time.temporal.ChronoUnit.MICROS);
        if(created.state()!=PaymentGateway.State.PENDING||
           created.providerReference()==null||
           !created.providerReference().matches("FAKE_[a-f0-9]{32}")||
           !created.expiresAt().isAfter(clock.instant())) {
            throw conflict("Invalid fake sandbox gateway response");
        }
        UUID attemptId=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO digital_payment_attempts(
                id,payment_intent_id,method,provider_key,provider_reference,
                idempotency_key,amount_idr,currency,state,created_at,expires_at)
            VALUES (?,?,?,'SANDBOX_FAKE',?,?,?,'IDR','PENDING',?,?)
            """,attemptId,intent.id(),method.name(),created.providerReference(),
            key,intent.due(),Timestamp.from(clock.instant()),
            Timestamp.from(persistedExpiry));
        return new Attempt(attemptId,orderId,method.name(),intent.due(),
            created.providerReference(),"PENDING",persistedExpiry,
            created.nonPayableDisplayHint(),SandboxFakePaymentGateway.ENVIRONMENT);
    }

    private Attempt response(Stored old,UUID orderId) {
        String hint=old.method().equals("QRIS")?"FAKE_QRIS_NOT_PAYABLE":
            "FAKE_VA_NOT_PAYABLE";
        return new Attempt(old.id(),orderId,old.method(),old.amount(),
            old.reference(),old.state(),old.expiresAt(),hint,
            SandboxFakePaymentGateway.ENVIRONMENT);
    }

    private ApiFailureException bad(String message) {
        return new ApiFailureException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message);
    }
    private ApiFailureException conflict(String message) {
        return new ApiFailureException(HttpStatus.CONFLICT,"CONFLICT",message);
    }
}
