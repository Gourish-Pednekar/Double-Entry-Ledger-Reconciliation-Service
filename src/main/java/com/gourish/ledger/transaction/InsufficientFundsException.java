package com.gourish.ledger.transaction;

import java.math.BigDecimal;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(Long accountId, BigDecimal balance, BigDecimal requested) {
        super("Insufficient funds in account " + accountId
                + ": balance=" + balance + ", requested=" + requested);
    }
}