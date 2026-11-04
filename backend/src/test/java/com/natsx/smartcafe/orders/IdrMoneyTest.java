package com.natsx.smartcafe.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class IdrMoneyTest {
    @Test void calculatesIntegerRupiahWithoutFloatingPoint() {
        assertEquals(75000L, IdrMoney.lineTotal(25000L, 3));
    }

    @Test void refusesNegativeValuesAndOverflow() {
        assertThrows(IllegalArgumentException.class, () -> IdrMoney.lineTotal(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> IdrMoney.lineTotal(1, 0));
        assertThrows(ArithmeticException.class, () -> IdrMoney.lineTotal(Long.MAX_VALUE, 2));
    }
}
