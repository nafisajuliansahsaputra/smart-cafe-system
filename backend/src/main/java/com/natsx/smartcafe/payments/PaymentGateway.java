package com.natsx.smartcafe.payments;

import java.time.Instant;
import java.util.UUID;

/**
 * Boundary for a future authenticated payment provider.
 *
 * Creation and status checks are NOT settlement: a domain service must validate
 * the persisted order, amount, merchant, environment, signature and provider
 * status before any PAID transition. No payment state is changed here.
 */
public interface PaymentGateway {
    enum Method { QRIS, VA }
    enum State { PENDING, SUCCEEDED, FAILED, EXPIRED }

    record CreateRequest(UUID orderId,long amountIdr,Method method,String idempotencyKey) {}
    record Created(String providerReference,String nonPayableDisplayHint,
                   Instant expiresAt,State state) {}
    record Status(String providerReference,UUID orderId,long amountIdr,
                  Method method,State state,String environment) {}

    Created create(CreateRequest request);
    Status lookup(String providerReference);
}
