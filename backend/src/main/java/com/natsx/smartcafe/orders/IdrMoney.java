package com.natsx.smartcafe.orders;

public final class IdrMoney {
    private IdrMoney() {}

    public static long lineTotal(long unitPriceIdr, int quantity) {
        if (unitPriceIdr < 0 || quantity < 1) {
            throw new IllegalArgumentException("Unit price and quantity must be positive");
        }
        return Math.multiplyExact(unitPriceIdr, quantity);
    }
}
