package com.gourish.ledger.transaction;

public record PostResult(TransactionResponse transaction, boolean created) {
}