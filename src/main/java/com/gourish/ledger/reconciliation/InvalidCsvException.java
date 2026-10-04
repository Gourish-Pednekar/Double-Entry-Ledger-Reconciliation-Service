package com.gourish.ledger.reconciliation;

public class InvalidCsvException extends RuntimeException {
    public InvalidCsvException(String message) {
        super(message);
    }
}