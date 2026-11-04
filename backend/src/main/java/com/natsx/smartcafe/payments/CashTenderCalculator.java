package com.natsx.smartcafe.payments;

public final class CashTenderCalculator {
    private CashTenderCalculator() {}

    public static CashTenderQuote quote(long amountDueIdr, long amountReceivedIdr,
                                        PaymentStatus currentPaymentState) {
        if (currentPaymentState != PaymentStatus.AWAITING_CASH) {
            throw new IllegalStateException("Cash tender requires an unsettled cash payment intent");
        }
        if (amountDueIdr <= 0 || amountReceivedIdr < amountDueIdr) {
            throw new IllegalArgumentException("Received cash must cover the positive amount due");
        }
        long change = Math.subtractExact(amountReceivedIdr, amountDueIdr);
        return new CashTenderQuote(amountDueIdr, amountReceivedIdr, change);
    }
}
