package com.gourish.ledger.reconciliation;

public class BankStatementNotFoundException extends RuntimeException {
    public BankStatementNotFoundException(Long id) {
        super("Bank statement " + id + " not found");
    }
}