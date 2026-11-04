package com.natsx.smartcafe.orders;

import com.natsx.smartcafe.payments.PaymentStatus;
import java.util.Objects;

/**
 * Pure domain transition guards for the cash service workflow.
 * These do not authorize staff, commit database changes or mark payments paid.
 * Command handlers must lock/version-check and persist any permitted transition.
 */
public final class CashOrderPolicy {
    private CashOrderPolicy() {}

    public static OrderStatus approve(OrderStatus current, PaymentStatus payment) {
        require(current == OrderStatus.SUBMITTED && payment == PaymentStatus.AWAITING_CASH,
            "Cash approval requires a submitted order awaiting cash");
        return OrderStatus.ACCEPTED;
    }

    public static boolean canAdmitToKitchen(OrderStatus current, PaymentStatus payment) {
        return current == OrderStatus.ACCEPTED
            && (payment == PaymentStatus.AWAITING_CASH || payment == PaymentStatus.PAID);
    }

    public static OrderStatus startPreparation(OrderStatus current, PaymentStatus payment) {
        require(canAdmitToKitchen(current, payment), "Order is not eligible for cash kitchen admission");
        return OrderStatus.PREPARING;
    }

    public static OrderStatus markReady(OrderStatus current) {
        require(current == OrderStatus.PREPARING, "Only preparing orders can become ready");
        return OrderStatus.READY;
    }

    public static OrderStatus markServed(OrderStatus current) {
        require(current == OrderStatus.READY, "Only ready orders can become served");
        return OrderStatus.SERVED;
    }

    private static void require(boolean permitted, String message) {
        if (!permitted) {
            throw new IllegalStateException(message);
        }
    }
}
