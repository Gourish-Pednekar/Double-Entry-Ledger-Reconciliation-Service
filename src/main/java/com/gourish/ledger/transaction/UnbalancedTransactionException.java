package com.gourish.ledger.transaction;

import java.math.BigDecimal;

public class UnbalancedTransactionException extends RuntimeException {
    public UnbalancedTransactionException(BigDecimal debits, BigDecimal credits) {
        super("Transaction is unbalanced: debits=" + debits + " credits=" + credits);
    }
}