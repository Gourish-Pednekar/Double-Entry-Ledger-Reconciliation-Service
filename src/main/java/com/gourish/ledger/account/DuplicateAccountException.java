package com.gourish.ledger.account;

public class DuplicateAccountException extends RuntimeException {
    public DuplicateAccountException(String code) {
        super("Account with code " + code + " already exists");
    }
}