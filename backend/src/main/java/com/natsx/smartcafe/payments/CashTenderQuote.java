package com.natsx.smartcafe.payments;

/**
 * Validated cash tender calculation, NOT proof of cash receipt.
 * Only a separate audited DB cash tender record may settle a payment intent.
 */
public record CashTenderQuote(long amountDueIdr, long amountReceivedIdr, long changeGivenIdr) {}
