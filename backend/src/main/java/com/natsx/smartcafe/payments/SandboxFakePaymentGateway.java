package com.natsx.smartcafe.payments;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic, process-local fake. Deliberately NOT a Spring bean or an
 * HTTP endpoint; never configure it as a live merchant gateway. Its status
 * is NOT provider-verified evidence of funds and must never mark an order PAID.
 */
public final class SandboxFakePaymentGateway implements PaymentGateway {
    public static final String ENVIRONMENT="SANDBOX_FAKE_NOT_MONEY";
    private static final Duration ATTEMPT_LIFETIME=Duration.ofMinutes(15);
    private static final long MAX_IDR=1_000_000_000_000L;
    private final Clock clock;
    private final ConcurrentHashMap<String,Attempt> byKey=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Attempt> byReference=new ConcurrentHashMap<>();

    private record Attempt(CreateRequest request,String reference,Instant expiry,
                           State initialState) {}

    public SandboxFakePaymentGateway(Clock clock) {
        this.clock=Objects.requireNonNull(clock);
    }

    @Override
    public Created create(CreateRequest request) {
        if(request==null||request.orderId()==null||request.method()==null
            ||request.amountIdr()<=0||request.amountIdr()>MAX_IDR
            ||request.idempotencyKey()==null
            ||!request.idempotencyKey().matches("[A-Za-z0-9_-]{8,128}")) {
            throw new IllegalArgumentException("Invalid sandbox fake payment request");
        }
        String scope=request.orderId()+":"+request.idempotencyKey();
        Attempt created=byKey.compute(scope,(ignored,prior)->{
            if(prior!=null) {
                if(!prior.request().equals(request)) {
                    throw new IllegalStateException("Idempotency key reused with another payment");
                }
                return prior;
            }
            String contents=ENVIRONMENT+"|"+request.orderId()+"|"+request.amountIdr()
                +"|"+request.method()+"|"+request.idempotencyKey();
            String ref="FAKE_"+UUID.nameUUIDFromBytes(
                contents.getBytes(StandardCharsets.UTF_8)).toString().replace("-","");
            Attempt next=new Attempt(request,ref,clock.instant().plus(ATTEMPT_LIFETIME),
                State.PENDING);
            byReference.put(ref,next);
            return next;
        });
        String hint=request.method()==Method.QRIS?
            "FAKE_QRIS_NOT_PAYABLE":"FAKE_VA_NOT_PAYABLE";
        return new Created(created.reference(),hint,created.expiry(),created.initialState());
    }

    @Override
    public Status lookup(String providerReference) {
        Attempt attempt=byReference.get(providerReference);
        if(attempt==null)throw new IllegalArgumentException("Unknown fake reference");
        State state=clock.instant().isBefore(attempt.expiry())?
            attempt.initialState():State.EXPIRED;
        return new Status(attempt.reference(),attempt.request().orderId(),
            attempt.request().amountIdr(),attempt.request().method(),state,ENVIRONMENT);
    }
}
