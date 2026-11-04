package com.natsx.smartcafe.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class CashTenderCalculatorTest {
    @Test void computesExactRupiahChange() {
        var quote = CashTenderCalculator.quote(47000, 50000, PaymentStatus.AWAITING_CASH);
        assertEquals(47000, quote.amountDueIdr());
        assertEquals(50000, quote.amountReceivedIdr());
        assertEquals(3000, quote.changeGivenIdr());
    }

    @Test void rejectsUnderpaymentAndInvalidDues() {
        assertThrows(IllegalArgumentException.class, () ->
            CashTenderCalculator.quote(47000, 46000, PaymentStatus.AWAITING_CASH));
        assertThrows(IllegalArgumentException.class, () ->
            CashTenderCalculator.quote(0, 0, PaymentStatus.AWAITING_CASH));
        assertThrows(IllegalArgumentException.class, () ->
            CashTenderCalculator.quote(-1, 100, PaymentStatus.AWAITING_CASH));
    }

    @Test void rejectsSecondOrUnrelatedCapture() {
        assertThrows(IllegalStateException.class, () ->
            CashTenderCalculator.quote(10000, 10000, PaymentStatus.PAID));
        assertThrows(IllegalStateException.class, () ->
            CashTenderCalculator.quote(10000, 10000, PaymentStatus.PENDING));
    }
}
