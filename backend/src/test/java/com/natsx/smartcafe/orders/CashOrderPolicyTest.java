package com.natsx.smartcafe.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.natsx.smartcafe.payments.PaymentStatus;
import org.junit.jupiter.api.Test;

class CashOrderPolicyTest {
    @Test void approvalAdmitsUnpaidCashWithoutSettlingPayment() {
        var payment = PaymentStatus.AWAITING_CASH;
        var order = CashOrderPolicy.approve(OrderStatus.SUBMITTED, payment);
        assertEquals(OrderStatus.ACCEPTED, order);
        assertEquals(PaymentStatus.AWAITING_CASH, payment);
        assertTrue(CashOrderPolicy.canAdmitToKitchen(order, payment));
    }

    @Test void cannotApproveUnpaidDigitalOrAlreadyApprovedOrder() {
        assertThrows(IllegalStateException.class, () ->
            CashOrderPolicy.approve(OrderStatus.SUBMITTED, PaymentStatus.PENDING));
        assertThrows(IllegalStateException.class, () ->
            CashOrderPolicy.approve(OrderStatus.ACCEPTED, PaymentStatus.AWAITING_CASH));
    }

    @Test void kitchenNeverReceivesSubmittedCashOrder() {
        assertFalse(CashOrderPolicy.canAdmitToKitchen(
            OrderStatus.SUBMITTED, PaymentStatus.AWAITING_CASH));
        assertThrows(IllegalStateException.class, () ->
            CashOrderPolicy.startPreparation(OrderStatus.SUBMITTED, PaymentStatus.AWAITING_CASH));
    }

    @Test void kitchenAndServingAdvanceWithoutDeclaringCashPaid() {
        var payment = PaymentStatus.AWAITING_CASH;
        var order = CashOrderPolicy.startPreparation(OrderStatus.ACCEPTED, payment);
        order = CashOrderPolicy.markReady(order);
        order = CashOrderPolicy.markServed(order);
        assertEquals(OrderStatus.SERVED, order);
        assertEquals(PaymentStatus.AWAITING_CASH, payment);
    }

    @Test void illegalKitchenAndServingTransitionsAreRejected() {
        assertThrows(IllegalStateException.class, () -> CashOrderPolicy.markReady(OrderStatus.ACCEPTED));
        assertThrows(IllegalStateException.class, () -> CashOrderPolicy.markServed(OrderStatus.PREPARING));
        assertThrows(IllegalStateException.class, () ->
            CashOrderPolicy.startPreparation(OrderStatus.REJECTED, PaymentStatus.PAID));
    }
}
