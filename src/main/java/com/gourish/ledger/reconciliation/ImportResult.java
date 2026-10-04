package com.gourish.ledger.reconciliation;

public record ImportResult(Long statementId, Long accountId, int lineCount) {
}