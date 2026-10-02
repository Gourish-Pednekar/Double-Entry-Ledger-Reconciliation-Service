package com.gourish.ledger.account;

import java.math.BigDecimal;

public record BalanceResponse(Long accountId, String code, AccountType type, String currency,
        BigDecimal totalDebits, BigDecimal totalCredits, BigDecimal balance) {
}